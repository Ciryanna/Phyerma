package de.rwth_aachen.phyphox.ai;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.fail;

import org.json.JSONArray;
import org.json.JSONObject;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;

@RunWith(RobolectricTestRunner.class)
@Config(sdk = 35)
public class DeepSeekClientTest {

    @Test
    public void requestAsksForTheFlashModelWithoutThinking() throws Exception {
        JSONArray messages = new JSONArray().put(new JSONObject().put("role", "user").put("content", "hi"));
        JSONObject body = new JSONObject(DeepSeekClient.requestBody(messages));
        assertEquals("deepseek-flash", body.getString("model"));
        assertEquals("disabled", body.getJSONObject("thinking").getString("type"));
        assertFalse(body.getBoolean("stream"));
        assertEquals("hi", body.getJSONArray("messages").getJSONObject(0).getString("content"));
    }

    @Test
    public void answerTextIsExtracted() throws Exception {
        String body = "{\"choices\":[{\"message\":{\"role\":\"assistant\",\"content\":\"hello\"}}]}";
        assertEquals("hello", DeepSeekClient.parseResponse(200, body));
    }

    @Test
    public void errorStatusCarriesTheServiceMessage() {
        try {
            DeepSeekClient.parseResponse(401, "{\"error\":{\"message\":\"Authentication Fails\"}}");
            fail("expected an ApiException");
        } catch (DeepSeekClient.ApiException e) {
            assertEquals(401, e.status);
            assertEquals("Authentication Fails", e.getMessage());
        }
    }

    @Test
    public void garbageIsAnErrorNotACrash() {
        try {
            DeepSeekClient.parseResponse(200, "<html>proxy</html>");
            fail("expected an ApiException");
        } catch (DeepSeekClient.ApiException e) {
            assertEquals(200, e.status);
        }
    }

    @Test
    public void nonJsonErrorBodyIsShownAsIs() {
        try {
            DeepSeekClient.parseResponse(502, "Bad Gateway");
            fail("expected an ApiException");
        } catch (DeepSeekClient.ApiException e) {
            assertEquals("Bad Gateway", e.getMessage());
        }
    }
}
