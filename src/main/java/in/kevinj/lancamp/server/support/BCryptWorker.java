package in.kevinj.lancamp.server.support;

import org.mindrot.jbcrypt.BCrypt;
import org.vertx.java.core.eventbus.Message;
import org.vertx.java.core.json.JsonObject;
import org.vertx.java.platform.Verticle;

public class BCryptWorker extends Verticle {
	private String address;
	private int log_rounds;

	@Override
	public void start() {
		super.start();

		this.address = container.config().getString("address", "bcrypt");
		this.log_rounds = container.config().getInteger("log_rounds", 10).intValue();

		vertx.eventBus().registerLocalHandler(address + ".hash", this::doHash);
		vertx.eventBus().registerLocalHandler(address + ".check", this::doCheck);
	}

	private void doHash(Message<JsonObject> message) {
		String plaintext = message.body().getString("plaintext");
		if (plaintext == null) {
			//message.fail(1, "Missing required parameter (plaintext)");
			message.reply(new JsonObject().putString("status", "error").putString("message", "Missing required parameter (plaintext)"));
			return;
		}
		int log_rounds = message.body().getInteger("log_rounds", this.log_rounds).intValue();
		String hashed = BCrypt.hashpw(plaintext, BCrypt.gensalt(log_rounds));
		message.reply(new JsonObject().putString("status", "ok").putString("hashed", hashed));
	}

	private void doCheck(Message<JsonObject> message) {
		String plaintext = message.body().getString("plaintext");
		if (plaintext == null) {
			//message.fail(1, "Missing required parameter (plaintext)");
			message.reply(new JsonObject().putString("status", "error").putString("message", "Missing required parameter (plaintext)"));
			return;
		}
		String hashed = message.body().getString("hashed");
		if (hashed == null) {
			//message.fail(1, "Missing required parameter (hashed)");
			message.reply(new JsonObject().putString("status", "error").putString("message", "Missing required parameter (hashed)"));
			return;
		}
		Boolean result = Boolean.valueOf(BCrypt.checkpw(plaintext, hashed));
		message.reply(new JsonObject().putString("status", "ok").putBoolean("match", result));
	}
}
