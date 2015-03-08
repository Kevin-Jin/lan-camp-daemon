package in.kevinj.lancamp.server.controller;

import in.kevinj.lancamp.server.support.DatabaseUtil;
import in.kevinj.lancamp.server.support.UserAuth;

import java.util.HashMap;
import java.util.Map;

import org.vertx.java.core.Vertx;
import org.vertx.java.core.json.JsonArray;
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
			if (error.isEmpty())
				resp.end(new JsonObject().putString("user", username));
			else
				resp.end(new JsonObject().putString("error", error));
		});
	}

	private void updateOne(YokeRequest req, int i, JsonArray apps, Map<Integer, String> errors) {
		if (i >= apps.size())
			return;

		JsonObject app = apps.get(i);
		if (!app.containsField("clicks"))
			app.putNumber("clicks", 0);
		if (!app.containsField("keys"))
			app.putNumber("keys", 0);
		if (!app.containsField("times"))
			app.putNumber("times", 0);

		JsonObject criteria = new JsonObject()
			.putNumber("_id", UserAuth.getCurrentUserId(req))
			.putString("apps.app", app.getString("app"))
		;
		JsonObject objNew = new JsonObject()
			.putObject("$inc", new JsonObject()
				.putNumber("apps.$.clicks", app.getNumber("clicks"))
				.putNumber("apps.$.keys", app.getNumber("keys"))
				.putNumber("apps.$.times", app.getNumber("times"))
			)
		;
		JsonObject criteria2 = new JsonObject()
			.putNumber("_id", UserAuth.getCurrentUserId(req))
		;
		JsonObject objNew2 = new JsonObject()
			.putObject("$push", new JsonObject()
				.putObject("apps", app)
			)
		;

		DatabaseUtil.update(vertx, "user", criteria, objNew, error -> {
			container.logger().warn("Could not save stats", error);
			errors.put(i, error.getMessage());
		}, updateResp -> {
			if (updateResp.left.body().getNumber("number").intValue() == 0) {
				//"upsert" the embedded document
				DatabaseUtil.update(vertx, "user", criteria2, objNew2, error -> {
					container.logger().warn("Could not save stats", error);
					errors.put(i, error.getMessage());
				}, updateResp2 -> {
					errors.put(i, "");
					updateOne(req, i + 1, apps, errors);
				});
			} else {
				errors.put(i, "");
				updateOne(req, i + 1, apps, errors);
			}
		});
	}

	public void update(YokeRequest req) {
		if (UserAuth.getCurrentUserId(req) == -1) {
			req.response().end(new JsonObject().putString("error", "Not logged in."));
			return;
		}
		JsonObject json = req.body();
		Map<Integer, String> errors = new HashMap<>();
		updateOne(req, 0, json.getArray("apps"), errors);
		for (Map.Entry<Integer, String> error : errors.entrySet()) {
			if (!error.getValue().isEmpty()) {
				req.response().end(new JsonObject().putString("error", error.getValue()));
				return;
			}
		}
		req.response().end(new JsonObject().putString("status", "ok"));
	}
}
