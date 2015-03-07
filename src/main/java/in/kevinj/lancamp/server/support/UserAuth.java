package in.kevinj.lancamp.server.support;

import in.kevinj.lancamp.server.Boot;

import java.io.UnsupportedEncodingException;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.UUID;

import org.vertx.java.core.Handler;
import org.vertx.java.core.Vertx;
import org.vertx.java.core.json.JsonArray;
import org.vertx.java.core.json.JsonObject;
import org.vertx.java.platform.Container;

import com.jetdrone.vertx.yoke.core.YokeCookie;
import com.jetdrone.vertx.yoke.middleware.YokeRequest;

public class UserAuth {
	public static final int EBUS_TIMEOUT = 5000;
	/**
	 * Not a timeout in that it does not reset with active usage.
	 * This is the maximum amount of time a session token will be valid for
	 * before user is logged out or the persistent login token is revalidated.
	 */
	private static final long SESSION_LENGTH = 12 * 60 * 60 * 1000;

	public static String sign(String str) {
		MessageDigest md = null;
		try {
			md = MessageDigest.getInstance("SHA-1");
			byte[] b = md.digest((str + CookieUtil.COOKIE_KEY).getBytes("utf-8"));

			String result = "";
			for (int i = 0; i < b.length; i++)
				result += Integer.toString((b[i] & 0xff) + 0x100, 16).substring(1);
			return result;
		} catch (NoSuchAlgorithmException | UnsupportedEncodingException e) {
			e.printStackTrace();
			return null;
		}
	}

	public static boolean matchSign(String expectedSignature, String toSign) {
		if (expectedSignature == null || toSign == null)
			return false;

		String compareTo = sign(toSign);
		if (compareTo.length() != expectedSignature.length())
			return false;

		//no early outs - compare every character so that we are not susceptible to timing attacks
		boolean ok = true;
		for (int i = compareTo.length() - 1; i >= 0; --i)
			ok &= (compareTo.charAt(i) == expectedSignature.charAt(i)); 
		return ok;
	}

	public static int getCurrentUserId(YokeRequest request) {
		String cookie = CookieUtil.getCookie(request, "auth_session");
		if (cookie == null)
			return -1;
		return Integer.parseInt(cookie.substring(0, cookie.indexOf('~')));
	}

	/**
	 * {@code String joined = join(delimiter, elements)} is the inverse operation of
	 * {@code String[] elements = joined.split(delimiter)}.
	 * @param array
	 * @param delimiter
	 * @return
	 */
	private static String join(String delimiter, Object... elements) {
		if (elements.length == 0)
			return "";

		if (elements.length == 1)
			return elements[0].toString();

		StringBuilder sb = new StringBuilder();
		for (int i = 0; i < elements.length - 1; i++)
			sb.append(elements[i]).append(delimiter);
		sb.append(elements[elements.length - 1]);
		return sb.toString();
	}

	public static void createPersistentLoginCookie(Vertx vertx, Container container, YokeRequest request, Handler<String> handler) {
		int userId = getCurrentUserId(request);
		if (userId == -1) {
			handler.handle("");
			return; //malformed or nonexistent loggedInUserId value in session cookie
		}

		String newToken = UUID.randomUUID().toString();
		//also store on server side so we can periodically cleanup persistentlogin table
		//if user never again logs in from a remembered client
		long expireTime = System.currentTimeMillis() + CookieUtil.COOKIE_EXPIRE_INTERVAL_IN_SECONDS * 1000;

		Handler<Throwable> failHandler = failure -> {
			Throwable cause = failure.getCause();
			if (cause == null)
				container.logger().warn("Could not create persistent login, database error: " + failure.getMessage());
			else
				container.logger().warn("Could not create persistent login, database error: " + failure.getMessage(), cause);
			handler.handle("A database error occurred and we are fixing it. Try again later.");
		};

		DatabaseUtil.beginTransaction(vertx, failHandler, beginResp -> {
			DatabaseUtil.preparedStatement(beginResp.left, "INSERT INTO `persistentlogin` (`accountid`,`tokenhash`,`expiretime`) VALUES (?,?,?)", new JsonArray().add(userId).add(sign(newToken)).add(Long.valueOf(expireTime)), failHandler, insertResp -> {
				DatabaseUtil.getGeneratedKey(insertResp.left, failHandler, uidResp -> {
					DatabaseUtil.endTransaction(uidResp.left, failHandler, endResp -> {
						//make sure we update other usages of the auth cookie if we change the parameters that are joined
						YokeCookie cookie = new YokeCookie("auth_persistent", CookieUtil.COOKIE_SIGNER);
						cookie.setMaxAge(CookieUtil.COOKIE_EXPIRE_INTERVAL_IN_SECONDS);
						cookie.setPath("/");
						cookie.setValue(join("~", uidResp.right.toString(), newToken));
						CookieUtil.setCookie(request, cookie);
						handler.handle("");
					});
				});
			});
		});
	}

	public static void validateSessionCookie(Vertx vertx, Container container, YokeRequest request, Handler<String> handler) {
		String cookie = CookieUtil.getCookie(request, "auth_session");
		if (cookie == null) {
			handler.handle("");
			return;
		}
		long passwordIteration = -1; //TODO: when user changes password, update this value
		String[] cookieParams = cookie.split("~");
		if (cookieParams.length != 3 || Long.parseLong(cookieParams[1]) != passwordIteration || Long.parseLong(cookieParams[2]) < System.currentTimeMillis()) {
			doLogoff(vertx, container, request, handler);
			return;
		}
		handler.handle("");
	}

	public static void loadRememberedClient(Vertx vertx, Container container, YokeRequest request, Handler<String> handler) {
		int userId = getCurrentUserId(request);
		if (userId != -1) {
			handler.handle("");
			return; //we're already logged in, no need to load the persistent login cookie
		}

		String authCookie = CookieUtil.getCookie(request, "auth_persistent");
		if (authCookie == null) {
			handler.handle("");
			return; //no persistent login cookie was set, user is not logged in
		}

		String[] authParams = authCookie.split("~");
		if (authParams.length != 2) {
			handler.handle("");
			return; //malformed persistent login cookie
		}

		int uniqueId;
		try {
			uniqueId = Integer.parseInt(authParams[0]);
		} catch (NumberFormatException e) {
			handler.handle("");
			return; //malformed persistent login cookie
		}

		String token = authParams[1];

		Handler<Throwable> failHandler = failure -> {
			Throwable cause = failure.getCause();
			if (cause == null)
				container.logger().warn("Could not load persistent login, database error: " + failure.getMessage());
			else
				container.logger().warn("Could not load persistent login, database error: " + failure.getMessage(), cause);
			handler.handle("A database error occurred and we are fixing it. Try again later.");
		};

		DatabaseUtil.preparedStatement(vertx, "SELECT `accountid`,`tokenhash`,`expiretime` FROM `persistentlogin` WHERE `uniqueid` = ?", new JsonArray().add(Integer.valueOf(uniqueId)), failHandler, queryResp -> {
			if (!queryResp.right.hasNext() || !matchSign(queryResp.right.next().getValue(1), token) || queryResp.right.getLong(2) <= System.currentTimeMillis()) {
				//invalid persistent login cookie
				deletePersistentLoginCookie(vertx, container, request, true, handler);
				return;
			}
			long passwordIteration = -1; //TODO: when user changes password, update this value
			CookieUtil.setCookie(request, "auth_session", join("~", queryResp.right.getValue(0), passwordIteration, Long.valueOf(System.currentTimeMillis() + SESSION_LENGTH)));
			//set a new auth cookie so that if the old auth cookie sniffed and stolen, it will be invalid,
			//minimizing the impact of sniffed cookies
			deletePersistentLoginCookie(vertx, container, request, false, status -> {
				if ("".equals(status))
					createPersistentLoginCookie(vertx, container, request, handler);
				else
					handler.handle(status);
			});
		});
	}

	public static void deletePersistentLoginCookie(Vertx vertx, Container container, YokeRequest request, boolean discard, Handler<String> handler) {
		String authCookie = CookieUtil.getCookie(request, "auth_persistent");
		if (authCookie == null) {
			handler.handle("");
			return; //no persistent login cookie was set, user is not logged in
		}

		String[] authParams = authCookie.split("~");
		if (authParams.length != 2) {
			handler.handle("");
			return; //malformed persistent login cookie
		}

		int uniqueId;
		try {
			uniqueId = Integer.parseInt(authParams[0]);
		} catch (NumberFormatException e) {
			handler.handle("");
			return; //malformed persistent login cookie
		}

		if (discard)
			CookieUtil.discardCookie(request, "auth_persistent");

		Handler<Throwable> failHandler = failure -> {
			Throwable cause = failure.getCause();
			if (cause == null)
				container.logger().warn("Could not delete persistent login, database error: " + failure.getMessage());
			else
				container.logger().warn("Could not delete persistent login, database error: " + failure.getMessage(), cause);
			handler.handle("A database error occurred and we are fixing it. Try again later.");
		};

		DatabaseUtil.preparedStatement(vertx, "DELETE FROM `persistentlogin` WHERE `uniqueid` = ? OR (`accountid` = ? AND `expiretime` <= ?)", new JsonArray().add(Integer.valueOf(uniqueId)).add(Integer.valueOf(getCurrentUserId(request))).add(Long.valueOf(System.currentTimeMillis())), failHandler, delResp -> {
			handler.handle("");
		});
	}

	public static void doLogin(Vertx vertx, Container container, YokeRequest request, String email, String password, Handler<String> handler) {
		DatabaseUtil.preparedStatement(vertx, "SELECT `id`, `organization`, `password` FROM `accounts` WHERE `email` = ?", new JsonArray().add(email), failure -> {
			Throwable cause = failure.getCause();
			if (cause == null)
				container.logger().warn("Could not login account, database error: " + failure.getMessage());
			else
				container.logger().warn("Could not login account, database error: " + failure.getMessage(), cause);
			handler.handle("A database error occurred and we are fixing it. Try again later.");
		}, queryResp -> {
			if (!queryResp.right.hasNext()) {
				handler.handle("The username or password is incorrect. Try again.");
				return;
			}
			String hash = queryResp.right.next().getValue(2);
			vertx.eventBus().<JsonObject>sendWithTimeout(Boot.BC_HANDLE + ".check", new JsonObject()
				.putString("plaintext", password)
				.putString("hashed", hash),
				EBUS_TIMEOUT,
				hashResp -> {
					if (hashResp.failed()) {
						container.logger().warn("Could not login account, passhash operation timed out", hashResp.cause());
						handler.handle("A hash error occurred and we are fixing it. Try again later.");
						return;
					}
					switch (hashResp.result().body().getString("status")) {
						default:
							container.logger().warn("Could not login account, passhash error: " + hashResp.result().body().getString("message"));
							handler.handle("A hash error occurred and we are fixing it. Try again later.");
							break;
						case "ok":
							if (!hashResp.result().body().getBoolean("match").booleanValue()) {
								handler.handle("The username or password is incorrect. Try again.");
								return;
							}
							//empty string signals successful login
							long passwordIteration = -1; //TODO: when user changes password, update this value
							CookieUtil.setCookie(request, "auth_session", join("~", queryResp.right.getValue(0), passwordIteration, Long.valueOf(System.currentTimeMillis() + 12 * 60 * 60 * 1000)));
							handler.handle("");
							break;
					}
				}
			);
		});
	}

	public static void doRegister(Vertx vertx, Container container, YokeRequest request, Handler<String> handler) {
		//TODO: implement
	}

	public static void doLogoff(Vertx vertx, Container container, YokeRequest request, Handler<String> handler) {
		CookieUtil.discardCookie(request, "auth_session");
		String rememberMe = CookieUtil.getCookie(request, "auth_persistent");
		if (rememberMe != null)
			deletePersistentLoginCookie(vertx, container, request, true, handler);
		else
			handler.handle("");
	}

	/**
	 * Changing the password will log off anyone logged into the account.
	 * @param vertx
	 * @param container
	 * @param request
	 * @param accountId
	 * @param newPass
	 * @param handler
	 */
	public static void changePassword(Vertx vertx, Container container, YokeRequest request, int accountId, char[] newPass, Handler<String> handler) {
		//update passwordIteration to invalidate any remaining auth_session cookies for the account
		//long passwordIteration = -1; //TODO: recalculate this
		//TODO: hash password and commit it and passwordIteration to DB
		Handler<Throwable> failHandler = failure -> {
			Throwable cause = failure.getCause();
			if (cause == null)
				container.logger().warn("Could not change password, database error: " + failure.getMessage());
			else
				container.logger().warn("Could not change password, database error: " + failure.getMessage(), cause);
			handler.handle("A database error occurred and we are fixing it. Try again later.");
		};

		DatabaseUtil.preparedStatement(vertx, "DELETE FROM `persistentlogin` WHERE `accountid` = ?", new JsonArray().add(Integer.valueOf(accountId)), failHandler, delResp -> {
			handler.handle("");
		});
	}

	/*private static boolean usernameInUse(Connection con, String userName) throws SQLException {
		PreparedStatement ps = null;
		ResultSet rs = null;
		try {
			ps = con.prepareStatement("SELECT 1 FROM `users` WHERE `loginname` = ? LIMIT 1");
			ps.setString(1, userName);
			rs = ps.executeQuery();
			return rs.next();
		} finally {
			DatabaseHelper.cleanup(null, ps, rs);
		}
	}*/

	/**
	 * 
	 * @param userName
	 * @param password
	 * @param email
	 * @param birthday
	 * @return the userId if user created successfully, or -1 if the username
	 * is already used, -2 if the email is already used, or -3 if an internal
	 * server error occurred.
	 */
	/*public static int getRegisterResult(String userName, String password, String email, String birthday) {
		Connection con = null;
		PreparedStatement ps = null;
		ResultSet rs = null;
		try {
			con = DB.getConnection();
			if (usernameInUse(con, userName))
				return -1;
			ps = con.prepareStatement("INSERT IGNORE INTO `users` (`loginname`,`password`,`email`,`dob`)"
					+ " VALUES (?,?,?,?)", PreparedStatement.RETURN_GENERATED_KEYS);
			ps.setString(1, userName);
			ps.setString(2, BCrypt.hashpw(password, BCrypt.gensalt()));
			ps.setString(3, email);
			ps.setString(4, birthday);
			ps.executeUpdate();
			rs = ps.getGeneratedKeys();
			if (!rs.next())
				return -2;
			return rs.getInt(1);
		} catch (SQLException e) {
			Logger.warn("Failed to register " + userName, e);
			return -3;
		} finally {
			DatabaseHelper.cleanup(con, ps, rs);
		}
	}*/
}
