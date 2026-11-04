package platform;

import static org.junit.jupiter.api.Assertions.assertEquals;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.Map;
import org.junit.jupiter.api.Test;

class MainTest {
  @Test
  void serializesJson() throws Exception {
    assertEquals("{\"ok\":true}", new ObjectMapper().writeValueAsString(Map.of("ok", true)));
  }
}
