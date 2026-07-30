package com.xinshuo.mindflow.user;

import com.xinshuo.mindflow.MindflowApplication;
import com.xinshuo.mindflow.framework.convention.Result;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.http.*;
import org.springframework.test.context.ActiveProfiles;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 用户模块集成测试——启动完整 Spring 容器，真实 HTTP 请求。
 *
 * <p>前置条件：
 * <ol>
 *   <li>application-local.yaml 配置正确</li>
 * </ol>
 */
@SpringBootTest(
        webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        classes = MindflowApplication.class
)
@ActiveProfiles("local")
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class AuthIntegrationTest {

    @LocalServerPort
    private int port;

    @Autowired
    private TestRestTemplate restTemplate;

    private static String token;

    private String url(String path) {
        return "http://localhost:" + port + "/api/mindflow" + path;
    }

    @Test
    @Order(1)
    void shouldLoginSuccessfully() {
        String body = """
                {
                    "username": "admin",
                    "password": "admin"
                }
                """;

        ResponseEntity<Result> response = post("/auth/login", body, Result.class);

        assertEquals(HttpStatus.OK, response.getStatusCode());
        Result result = response.getBody();
        assertNotNull(result);
        assertTrue(result.isSuccess(), "登录应成功: " + result.getMessage());

        var data = (java.util.Map<String, Object>) result.getData();
        token = (String) data.get("token");

        System.out.println("登录成功, token: " + token);
    }

    @Test
    @Order(2)
    void shouldReturnCurrentUser() {
        assertNotNull(token, "需要先登录获取 token");

        HttpHeaders headers = new HttpHeaders();
        headers.set("Authorization", token);
        HttpEntity<Void> request = new HttpEntity<>(headers);

        ResponseEntity<Result> response = restTemplate.exchange(
                url("/user/me"), HttpMethod.GET, request, Result.class);

        assertEquals(HttpStatus.OK, response.getStatusCode());
        Result result = response.getBody();
        assertNotNull(result);
        assertTrue(result.isSuccess(), "获取当前用户应成功: " + result.getMessage());

        var data = (java.util.Map<String, Object>) result.getData();
        assertEquals("admin", data.get("username"));
        System.out.println("当前用户: " + data.get("username") + ", role: " + data.get("role"));
    }

    @Test
    @Order(3)
    void shouldRejectUnauthenticatedRequest() {
        ResponseEntity<Result> response = restTemplate.exchange(
                url("/user/me"), HttpMethod.GET, null, Result.class);

        Result result = response.getBody();
        assertNotNull(result);
        assertFalse(result.isSuccess(), "未登录应返回失败");
        System.out.println("未登录返回: " + result.getMessage());
    }

    @Test
    @Order(4)
    void shouldFailWithWrongPassword() {
        String body = """
                {
                    "username": "admin",
                    "password": "wrongpassword"
                }
                """;

        ResponseEntity<Result> response = post("/auth/login", body, Result.class);

        Result result = response.getBody();
        assertNotNull(result);
        assertFalse(result.isSuccess(), "错误密码登录应失败");
        System.out.println("错误密码返回: " + result.getMessage());
    }

    @Test
    @Order(5)
    void shouldLogoutSuccessfully() {
        assertNotNull(token, "需要先登录获取 token");

        HttpHeaders headers = new HttpHeaders();
        headers.set("Authorization", token);
        HttpEntity<Void> request = new HttpEntity<>(headers);

        ResponseEntity<Result> response = restTemplate.exchange(
                url("/auth/logout"), HttpMethod.POST, request, Result.class);

        Result result = response.getBody();
        assertNotNull(result);
        assertTrue(result.isSuccess(), "登出应成功");
        System.out.println("登出成功");
    }

    private ResponseEntity<Result> post(String path, String body, Class<Result> clazz) {
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        HttpEntity<String> request = new HttpEntity<>(body, headers);
        return restTemplate.exchange(url(path), HttpMethod.POST, request, clazz);
    }
}
