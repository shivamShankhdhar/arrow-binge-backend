package arrow.binge.arrow_binge_backend;

import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import java.util.Map;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.verify;

class RootControllerTest {
    @Test
    void rootAndHealthReturnUp() {
        ExpiredDataCleanup cleanup = Mockito.mock(ExpiredDataCleanup.class);
        RootController controller = new RootController(cleanup, "");

        Map<String, Object> root = controller.root();
        assertEquals("UP", root.get("status"));
        assertEquals("arrow-binge-backend", root.get("service"));
        assertEquals("discovery-v3", root.get("version"));

        Map<String, Object> health = controller.health();
        assertEquals("UP", health.get("status"));
    }

    @Test
    void cleanupExecutesWhenAuthorized() {
        ExpiredDataCleanup cleanup = Mockito.mock(ExpiredDataCleanup.class);
        RootController controller = new RootController(cleanup, "my-secret");

        assertThrows(ApiException.class, () -> controller.runCleanup("Bearer wrong-secret"));
        assertThrows(ApiException.class, () -> controller.runCleanup(null));

        Map<String, Object> res = controller.runCleanup("Bearer my-secret");
        assertTrue((Boolean) res.get("ok"));
        verify(cleanup).clean();
    }
}
