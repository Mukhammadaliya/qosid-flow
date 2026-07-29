package uz.greenwhite.ops;

import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;

@SpringBootTest(properties = {
        "ops.settings-db-path=mem:opstest;DB_CLOSE_DELAY=-1",
        "ops.gateway.start-command=echo start",
        "ops.gateway.stop-command=echo stop"
})
class OpsApplicationTest {
    @Test
    void contextLoads() {
        // Fails until OpsApplication + a valid context (security, datasource) exist.
    }
}
