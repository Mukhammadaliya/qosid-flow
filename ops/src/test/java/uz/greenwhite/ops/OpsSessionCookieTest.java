package uz.greenwhite.ops;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

/** The renamed session cookie is container-level, so assert over a real port (MockMvc can't see it). */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class OpsSessionCookieTest {

    @Autowired TestRestTemplate rest;
    @MockitoBean AdminAuthDao authDao;

    @Test
    void loginIssuesOpsSessionCookie_notJsessionId() {
        when(authDao.verify(any(), any())).thenReturn(true);
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        ResponseEntity<Void> res = rest.postForEntity("/ops/api/login",
                new HttpEntity<>("{\"username\":\"admin\",\"password\":\"admin\"}", headers), Void.class);

        assertThat(res.getStatusCode().value()).isEqualTo(200);
        List<String> cookies = res.getHeaders().getOrDefault(HttpHeaders.SET_COOKIE, List.of());
        assertThat(cookies).anyMatch(c -> c.startsWith("OPS_SESSION="));
        assertThat(cookies).noneMatch(c -> c.startsWith("JSESSIONID="));
    }
}
