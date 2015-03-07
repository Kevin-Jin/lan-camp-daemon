package in.kevinj.lancamp.server.support;

import in.kevinj.lancamp.server.Boot;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Iterator;
import java.util.List;

import org.vertx.java.core.AsyncResult;
import org.vertx.java.core.Handler;
import org.vertx.java.core.Vertx;
import org.vertx.java.core.eventbus.Message;
import org.vertx.java.core.json.JsonArray;
import org.vertx.java.core.json.JsonObject;

public class DatabaseUtil {
	public static class QueryResult implements Iterable<JsonArray> {
		private final JsonObject base;
		private Iterator<Object> iterator;
		private JsonArray cursor;

		public QueryResult(JsonObject base) {
			this.base = base;
			this.iterator = base.containsField("results") ? base.getArray("results").iterator() : Collections.emptyIterator();
		}

		public int affectedRows() {
			return base.getNumber("rows").intValue();
		}

		public <T> T getValue(int index) {
			return cursor.get(index);
		}

		public int getInt(int index) {
			return this.<Number>getValue(index).intValue();
		}

		public long getLong(int index) {
			return this.<Number>getValue(index).longValue();
		}

		public double getDouble(int index) {
			return this.<Number>getValue(index).doubleValue();
		}

		public float getFloat(int index) {
			return this.<Number>getValue(index).floatValue();
		}

		public boolean getBoolean(int index) {
			return this.<Boolean>getValue(index).booleanValue();
		}

		public boolean hasNext() {
			return iterator.hasNext();
		}

		public QueryResult next() {
			if (iterator.hasNext())
				cursor = (JsonArray) iterator.next();
			else
				cursor = null;
			return this;
		}

		@Override
		public Iterator<JsonArray> iterator() {
			JsonArray results = base.getArray("results");
			List<JsonArray> rows = new ArrayList<>(results.size());
			results.forEach(obj -> rows.add((JsonArray) obj));
			return rows.iterator();
		}
	}

	private static void onSimpleResult(AsyncResult<Message<JsonObject>> dbResp, Handler<Throwable> failure, Handler<Pair<Message<JsonObject>, QueryResult>> success) {
		if (dbResp.failed()) {
			failure.handle(new Throwable("operation timed out", dbResp.cause()));
			return;
		}
		switch (dbResp.result().body().getString("status")) {
			default:
				if (dbResp.result().body().containsField("exception"))
					failure.handle(new Throwable(dbResp.result().body().getString("message") + "\n" + dbResp.result().body().getString("exception")));
				else
					failure.handle(new Throwable(dbResp.result().body().getString("message")));
				break;
			case "ok":
				success.handle(new Pair<>(dbResp.result(), new QueryResult(dbResp.result().body())));
				break;
		}
	}

	private static <T> void onGeneratedKeyResult(AsyncResult<Message<JsonObject>> dbResp, Handler<Throwable> failure, Handler<Pair<Message<JsonObject>, T>> success) {
		if (dbResp.failed()) {
			failure.handle(new Throwable("operation timed out", dbResp.cause()));
			return;
		}
		switch (dbResp.result().body().getString("status")) {
			default:
				if (dbResp.result().body().containsField("exception"))
					failure.handle(new Throwable(dbResp.result().body().getString("message") + "\n" + dbResp.result().body().getString("exception")));
				else
					failure.handle(new Throwable(dbResp.result().body().getString("message")));
				break;
			case "ok":
				if (dbResp.result().body().getNumber("rows").intValue() == 0)
					failure.handle(new Throwable("no rows"));
				else
					success.handle(new Pair<>(dbResp.result(), dbResp.result().body().getArray("results").<JsonArray>get(0).<T>get(0)));
				break;
		}
	}

	private static JsonObject makeBeginTransactionQuery() {
		return new JsonObject().putString("action", "begin");
	}

	public static void beginTransaction(Vertx vertx, Handler<Throwable> failure, Handler<Pair<Message<JsonObject>, QueryResult>> success) {
		vertx.eventBus().<JsonObject>sendWithTimeout(Boot.DB_HANDLE,
			makeBeginTransactionQuery(),
			UserAuth.EBUS_TIMEOUT,
			dbResp -> onSimpleResult(dbResp, failure, success)
		);
	}

	public static void beginTransaction(Message<?> replyTo, Handler<Throwable> failure, Handler<Pair<Message<JsonObject>, QueryResult>> success) {
		replyTo.<JsonObject>replyWithTimeout(
			makeBeginTransactionQuery(),
			UserAuth.EBUS_TIMEOUT,
			dbResp -> onSimpleResult(dbResp, failure, success)
		);
	}

	private static JsonObject makeEndTransactionQuery() {
		return new JsonObject().putString("action", "commit");
	}

	public static void endTransaction(Vertx vertx, Handler<Throwable> failure, Handler<Pair<Message<JsonObject>, QueryResult>> success) {
		vertx.eventBus().<JsonObject>sendWithTimeout(Boot.DB_HANDLE,
			makeEndTransactionQuery(),
			UserAuth.EBUS_TIMEOUT,
			dbResp -> onSimpleResult(dbResp, failure, success)
		);
	}

	public static void endTransaction(Message<?> replyTo, Handler<Throwable> failure, Handler<Pair<Message<JsonObject>, QueryResult>> success) {
		replyTo.<JsonObject>replyWithTimeout(
			makeEndTransactionQuery(),
			UserAuth.EBUS_TIMEOUT,
			dbResp -> onSimpleResult(dbResp, failure, success)
		);
	}

	private static JsonObject makePreparedStatementQuery(String statement, JsonArray value) {
		return new JsonObject().putString("action", "prepared").putString("statement", statement).putArray("values", value);
	}

	public static void preparedStatement(Vertx vertx, String statement, JsonArray value, Handler<Throwable> failure, Handler<Pair<Message<JsonObject>, QueryResult>> success) {
		vertx.eventBus().<JsonObject>sendWithTimeout(Boot.DB_HANDLE,
			makePreparedStatementQuery(statement, value),
			UserAuth.EBUS_TIMEOUT,
			dbResp -> onSimpleResult(dbResp, failure, success)
		);
	}

	public static void preparedStatement(Message<?> replyTo, String statement, JsonArray value, Handler<Throwable> failure, Handler<Pair<Message<JsonObject>, QueryResult>> success) {
		replyTo.<JsonObject>replyWithTimeout(
			makePreparedStatementQuery(statement, value),
			UserAuth.EBUS_TIMEOUT,
			dbResp -> onSimpleResult(dbResp, failure, success)
		);
	}

	private static JsonObject makeGetGeneratedKeyQuery() {
		return new JsonObject().putString("action", "raw").putString("command", "SELECT LAST_INSERT_ID()");
	}

	public static <T> void getGeneratedKey(Vertx vertx, Handler<Throwable> failure, Handler<Pair<Message<JsonObject>, T>> success) {
		vertx.eventBus().<JsonObject>sendWithTimeout(Boot.DB_HANDLE,
			makeGetGeneratedKeyQuery(),
			UserAuth.EBUS_TIMEOUT,
			dbResp -> onGeneratedKeyResult(dbResp, failure, success)
		);
	}

	public static <T> void getGeneratedKey(Message<?> replyTo, Handler<Throwable> failure, Handler<Pair<Message<JsonObject>, T>> success) {
		replyTo.<JsonObject>replyWithTimeout(
			makeGetGeneratedKeyQuery(),
			UserAuth.EBUS_TIMEOUT,
			dbResp -> onGeneratedKeyResult(dbResp, failure, success)
		);
	}
}
