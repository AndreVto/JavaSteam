package in.dragonbra.javasteam.networking.steam3;

import org.junit.jupiter.api.Test;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class WebSocketConnectionTest {
    @Test
    void repeatedDisconnectNotifiesOnce() throws InterruptedException {
        var connection = new WebSocketConnection();
        var calls = new AtomicInteger();
        var signaled = new CountDownLatch(1);
        connection.getDisconnected().addEventHandler((sender, args) -> {
            calls.incrementAndGet();
            signaled.countDown();
        });

        connection.disconnect(false);
        connection.disconnect(true);

        assertTrue(signaled.await(5, TimeUnit.SECONDS));
        assertEquals(1, calls.get());
    }
}
