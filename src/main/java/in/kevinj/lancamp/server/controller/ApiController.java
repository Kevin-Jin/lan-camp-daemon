package in.kevinj.lancamp.server.controller;

import in.kevinj.lancamp.server.support.UserAuth;

import org.vertx.java.core.Vertx;
import org.vertx.java.core.json.JsonObject;
import org.vertx.java.platform.Container;

import com.jetdrone.vertx.yoke.middleware.YokeRequest;
import com.jetdrone.vertx.yoke.middleware.YokeResponse;

public class ApiController {
	private final Vertx vertx;
	private final Container container;

	public ApiController(Vertx vertx, Container container) {
		this.vertx = vertx;
		this.container = container;
	}

	public void login(YokeRequest req) {
		req.expectMultiPart(false);
		JsonObject json = req.body();
		String username = json.getString("username");
		String password = json.getString("password");
		UserAuth.doLogin(vertx, container, req, username, password, error -> {
			YokeResponse resp = req.response().setContentType("application/json", "utf-8");
			if (error == null)
				resp.end(new JsonObject().putString("user", username));
			else
				resp.end(new JsonObject().putString("error", error));
		});
	}
}
