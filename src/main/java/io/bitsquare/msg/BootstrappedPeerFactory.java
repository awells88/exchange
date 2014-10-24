/*
 * This file is part of Bitsquare.
 *
 * Bitsquare is free software: you can redistribute it and/or modify it
 * under the terms of the GNU Affero General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or (at
 * your option) any later version.
 *
 * Bitsquare is distributed in the hope that it will be useful, but WITHOUT
 * ANY WARRANTY; without even the implied warranty of MERCHANTABILITY or
 * FITNESS FOR A PARTICULAR PURPOSE. See the GNU Affero General Public
 * License for more details.
 *
 * You should have received a copy of the GNU Affero General Public License
 * along with Bitsquare. If not, see <http://www.gnu.org/licenses/>.
 */

package io.bitsquare.msg;

import io.bitsquare.persistence.Persistence;

import com.google.common.util.concurrent.ListenableFuture;
import com.google.common.util.concurrent.SettableFuture;

import com.google.inject.name.Named;

import java.io.IOException;

import java.net.InetAddress;
import java.net.UnknownHostException;

import java.security.KeyPair;

import javax.annotation.concurrent.Immutable;

import javax.inject.Inject;

import javafx.application.Platform;
import javafx.beans.property.SimpleStringProperty;
import javafx.beans.property.StringProperty;

import net.tomp2p.dht.PeerBuilderDHT;
import net.tomp2p.dht.PeerDHT;
import net.tomp2p.dht.StorageLayer;
import net.tomp2p.futures.BaseFuture;
import net.tomp2p.futures.BaseFutureListener;
import net.tomp2p.futures.FutureDiscover;
import net.tomp2p.nat.FutureNAT;
import net.tomp2p.nat.FutureRelayNAT;
import net.tomp2p.nat.PeerBuilderNAT;
import net.tomp2p.nat.PeerNAT;
import net.tomp2p.p2p.Peer;
import net.tomp2p.p2p.PeerBuilder;
import net.tomp2p.peers.Number160;
import net.tomp2p.peers.PeerAddress;
import net.tomp2p.peers.PeerMapChangeListener;
import net.tomp2p.peers.PeerStatatistic;
import net.tomp2p.storage.Storage;

import org.jetbrains.annotations.NotNull;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import static io.bitsquare.msg.SeedNodeAddress.StaticSeedNodeAddresses;

/**
 * Creates a DHT peer and bootstrap to the network via a seed node
 */
@Immutable
public class BootstrappedPeerFactory {
    private static final Logger log = LoggerFactory.getLogger(BootstrappedPeerFactory.class);

    private KeyPair keyPair;
    private Storage storage;
    private final SeedNodeAddress seedNodeAddress;
    private final Persistence persistence;

    private final SettableFuture<PeerDHT> settableFuture = SettableFuture.create();
    public final StringProperty connectionState = new SimpleStringProperty();


    ///////////////////////////////////////////////////////////////////////////////////////////
    // Constructor
    ///////////////////////////////////////////////////////////////////////////////////////////

    @Inject
    public BootstrappedPeerFactory(Persistence persistence,
                                   @Named("defaultSeedNode") StaticSeedNodeAddresses defaultStaticSeedNodeAddresses) {
        this.persistence = persistence;
        this.seedNodeAddress = new SeedNodeAddress(defaultStaticSeedNodeAddresses);
    }


    ///////////////////////////////////////////////////////////////////////////////////////////
    // Setters
    ///////////////////////////////////////////////////////////////////////////////////////////

    public void setKeyPair(@NotNull KeyPair keyPair) {
        this.keyPair = keyPair;
    }

    public void setStorage(@NotNull Storage storage) {
        this.storage = storage;
    }


    ///////////////////////////////////////////////////////////////////////////////////////////
    // Public methods
    ///////////////////////////////////////////////////////////////////////////////////////////

    public ListenableFuture<PeerDHT> start(int port) {
        try {
            Peer peer = new PeerBuilder(keyPair).ports(port).behindFirewall().start();
            PeerDHT peerDHT = new PeerBuilderDHT(peer).storageLayer(new StorageLayer(storage)).start();

            peer.peerBean().peerMap().addPeerMapChangeListener(new PeerMapChangeListener() {
                @Override
                public void peerInserted(PeerAddress peerAddress, boolean verified) {
                    log.debug("Peer inserted: peerAddress=" + peerAddress + ", verified=" + verified);
                }

                @Override
                public void peerRemoved(PeerAddress peerAddress, PeerStatatistic peerStatistics) {
                    log.debug("Peer removed: peerAddress=" + peerAddress + ", peerStatistics=" + peerStatistics);
                }

                @Override
                public void peerUpdated(PeerAddress peerAddress, PeerStatatistic peerStatistics) {
                    // log.debug("Peer updated: peerAddress=" + peerAddress + ", peerStatistics=" + peerStatistics);
                }
            });

            // We save last successful bootstrap method.
            // Reset it to "default" after 5 start ups.
            Object bootstrapCounterObject = persistence.read(this, "bootstrapCounter");
            int bootstrapCounter = 0;
            if (bootstrapCounterObject instanceof Integer)
                bootstrapCounter = (int) bootstrapCounterObject + 1;

            if (bootstrapCounter > 5)
                persistence.write(this, "lastSuccessfulBootstrap", "default");

            persistence.write(this, "bootstrapCounter", bootstrapCounter);


            String lastSuccessfulBootstrap = (String) persistence.read(this, "lastSuccessfulBootstrap");
            if (lastSuccessfulBootstrap == null)
                lastSuccessfulBootstrap = "default";

            // TODO
            // lastSuccessfulBootstrap = "default";

            log.debug("lastSuccessfulBootstrap = " + lastSuccessfulBootstrap);
            FutureDiscover futureDiscover;
            switch (lastSuccessfulBootstrap) {
                case "relay":
                    futureDiscover = peerDHT.peer().discover().peerAddress(getBootstrapAddress()).start();
                    PeerNAT peerNAT = new PeerBuilderNAT(peerDHT.peer()).start();
                    FutureNAT futureNAT = peerNAT.startSetupPortforwarding(futureDiscover);
                    bootstrapWithRelay(peerDHT, peerNAT, futureDiscover, futureNAT);
                    break;
                case "portForwarding":
                    futureDiscover = peerDHT.peer().discover().peerAddress(getBootstrapAddress()).start();
                    tryPortForwarding(peerDHT, futureDiscover);
                    break;
                case "default":
                default:
                    discover(peerDHT);
                    break;
            }
        } catch (IOException e) {
            setState("Cannot create peer with port: " + port + ". Exeption: " + e, false);
            settableFuture.setException(e);
        }

        return settableFuture;
    }

    // 1. Attempt: Try to discover our outside visible address
    private void discover(PeerDHT peerDHT) {
        FutureDiscover futureDiscover = peerDHT.peer().discover().peerAddress(getBootstrapAddress()).start();
        futureDiscover.addListener(new BaseFutureListener<BaseFuture>() {
            @Override
            public void operationComplete(BaseFuture future) throws Exception {
                if (future.isSuccess()) {
                    setState("We are visible to other peers: My address visible to " +
                            "the outside is " + futureDiscover.peerAddress());
                    settableFuture.set(peerDHT);
                    persistence.write(BootstrappedPeerFactory.this, "lastSuccessfulBootstrap", "default");
                }
                else {
                    log.warn("Discover has failed. Reason: " + futureDiscover.failedReason());
                    setState("We are probably behind a NAT and not reachable to other peers. " +
                            "We try port forwarding as next step.");
                    tryPortForwarding(peerDHT, futureDiscover);
                }
            }

            @Override
            public void exceptionCaught(Throwable t) throws Exception {
                setState("Exception at discover: " + t.getMessage(), false);
                settableFuture.setException(t);
                peerDHT.shutdown().awaitUninterruptibly();
            }
        });
    }

    // 2. Attempt: Try to set up port forwarding with UPNP and NAT-PMP
    private void tryPortForwarding(PeerDHT peerDHT, FutureDiscover futureDiscover) {
        PeerNAT peerNAT = new PeerBuilderNAT(peerDHT.peer()).start();
        FutureNAT futureNAT = peerNAT.startSetupPortforwarding(futureDiscover);
        futureNAT.addListener(new BaseFutureListener<BaseFuture>() {
            @Override
            public void operationComplete(BaseFuture future) throws Exception {
                if (future.isSuccess()) {
                    setState("Automatic port forwarding is setup. Address = " + futureNAT.peerAddress());
                    // we need a second discover process
                    discoverAfterPortForwarding(peerDHT);
                }
                else {
                    log.warn("Port forwarding has failed. Reason: " + futureNAT.failedReason());
                    setState("Port forwarding has failed. We try to use a relay as next step.");
                    bootstrapWithRelay(peerDHT, peerNAT, futureDiscover, futureNAT);
                }
            }

            @Override
            public void exceptionCaught(Throwable t) throws Exception {
                setState("Exception at port forwarding: " + t.getMessage(), false);
                settableFuture.setException(t);
                peerDHT.shutdown().awaitUninterruptibly();
            }
        });
    }

    // Try to determine our outside visible address after port forwarding is setup
    private void discoverAfterPortForwarding(PeerDHT peerDHT) {
        FutureDiscover futureDiscover = peerDHT.peer().discover().peerAddress(getBootstrapAddress()).start();
        futureDiscover.addListener(new BaseFutureListener<BaseFuture>() {
            @Override
            public void operationComplete(BaseFuture future) throws Exception {
                if (future.isSuccess()) {
                    setState("Discover with automatic port forwarding was successful. " +
                            "My address visible to the outside is = " + futureDiscover.peerAddress());
                    settableFuture.set(peerDHT);
                    persistence.write(BootstrappedPeerFactory.this, "lastSuccessfulBootstrap", "portForwarding");
                }
                else {
                    setState("Discover with automatic port forwarding has failed " + futureDiscover
                            .failedReason(), false);
                    settableFuture.setException(new Exception("Discover with automatic port forwarding failed " +
                            futureDiscover.failedReason()));
                    persistence.write(BootstrappedPeerFactory.this, "lastSuccessfulBootstrap", "default");
                    peerDHT.shutdown().awaitUninterruptibly();
                }
            }

            @Override
            public void exceptionCaught(Throwable t) throws Exception {
                setState("Exception at discover: " + t, false);
                persistence.write(BootstrappedPeerFactory.this, "lastSuccessfulBootstrap", "default");
                settableFuture.setException(t);
                peerDHT.shutdown().awaitUninterruptibly();
            }
        });
    }

    // 3. Attempt: We try to use another peer as relay
    private void bootstrapWithRelay(PeerDHT peerDHT, PeerNAT peerNAT, FutureDiscover futureDiscover,
                                    FutureNAT futureNAT) {
        FutureRelayNAT futureRelayNAT = peerNAT.startRelay(futureDiscover, futureNAT);
        futureRelayNAT.addListener(new BaseFutureListener<BaseFuture>() {
            @Override
            public void operationComplete(BaseFuture future) throws Exception {
                if (future.isSuccess()) {
                    setState("Bootstrap using relay was successful. " +
                            "My address visible to the outside is = " + futureDiscover.peerAddress());
                    settableFuture.set(peerDHT);
                    persistence.write(BootstrappedPeerFactory.this, "lastSuccessfulBootstrap", "relay");
                }
                else {
                    setState("Bootstrap using relay has failed " + futureDiscover.failedReason(), false);
                    settableFuture.setException(new Exception("Bootstrap using relay failed " +
                            futureDiscover.failedReason()));
                    persistence.write(BootstrappedPeerFactory.this, "lastSuccessfulBootstrap", "default");
                    futureRelayNAT.shutdown();
                    peerDHT.shutdown().awaitUninterruptibly();
                }
            }

            @Override
            public void exceptionCaught(Throwable t) throws Exception {
                setState("Exception at bootstrapWithRelay: " + t, false);
                persistence.write(BootstrappedPeerFactory.this, "lastSuccessfulBootstrap", "default");
                settableFuture.setException(t);
                futureRelayNAT.shutdown();
                peerDHT.shutdown().awaitUninterruptibly();
            }
        });

    }

    private PeerAddress getBootstrapAddress() {
        try {
            return new PeerAddress(Number160.createHash(seedNodeAddress.getId()),
                    InetAddress.getByName(seedNodeAddress.getIp()),
                    seedNodeAddress.getPort(),
                    seedNodeAddress.getPort());
        } catch (UnknownHostException e) {
            log.error("getBootstrapAddress failed: " + e.getMessage());
            return null;
        }
    }

    private void setState(String state) {
        setState(state, true);
    }

    private void setState(String state, boolean isSuccess) {
        if (isSuccess)
            log.info(state);
        else
            log.error(state);
        Platform.runLater(() -> connectionState.set(state));
    }
}
