package in.kevinj.lancamp.server.controller;

import org.vertx.java.core.Vertx;
import org.vertx.java.platform.Container;

import com.jetdrone.vertx.yoke.middleware.YokeRequest;

public class AuthenticationController {
	private final Vertx vertx;
	private final Container container;

	public AuthenticationController(Vertx vertx, Container container) {
		this.vertx = vertx;
		this.container = container;
	}

	public void loginPage(YokeRequest req) {
		req.put("pagename", "LanCamp");
		req.response().render("in.kevinj.lancamp.server.login.soy");
	}

	public void login(YokeRequest req) {
		
	}
}
