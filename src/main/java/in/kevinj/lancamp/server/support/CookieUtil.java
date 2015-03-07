package in.kevinj.lancamp.server.support;

import io.netty.handler.codec.http.DefaultCookie;

import java.util.HashMap;
import java.util.Map;
import java.util.WeakHashMap;

import javax.crypto.Mac;

import com.jetdrone.vertx.yoke.core.YokeCookie;
import com.jetdrone.vertx.yoke.middleware.YokeRequest;
import com.jetdrone.vertx.yoke.security.SecretSecurity;

public class CookieUtil {
	public static final int COOKIE_EXPIRE_INTERVAL_IN_SECONDS = 60 * 60 * 24 * 15;
	/**
	 * Changing this value will log out anybody logged into the site.
	 */
	public static final String COOKIE_KEY = "xcv224536ufg";
	public static final Mac COOKIE_SIGNER = new SecretSecurity(COOKIE_KEY).getMac("HmacSHA256");
	private static final Map<YokeRequest, Map<String, YokeCookie>> newCookies = new WeakHashMap<>();

	public static String getCookie(YokeRequest request, String key) {
		YokeCookie cookie = null;
		Map<String, YokeCookie> additionalCookies = newCookies.get(request);//request.get("newCookies");
		if (additionalCookies != null) {
			cookie = additionalCookies.get(key);
			if (cookie == null && additionalCookies.containsKey(key))
				return null; //discarded cookie
		}
		if (cookie == null)
			cookie = request.getCookie(key); //cookie does not exist in newCookies
		if (cookie == null || cookie.getMaxAge() <= 0 && cookie.getMaxAge() != Long.MIN_VALUE)
			return null; //cookie does not exist anywhere
		return cookie.getUnsignedValue();
	}

	public static void setCookie(YokeRequest request, YokeCookie cookie) {
		if (!cookie.isSigned())
			cookie.sign();
		request.response().addCookie(cookie);

		Map<String, YokeCookie> additionalCookies = newCookies.get(request);//request.get("newCookies");
		if (additionalCookies == null) {
			additionalCookies = new HashMap<>();
			newCookies.put(request, additionalCookies);//request.put("newCookies", additionalCookies);
		}
		additionalCookies.put(cookie.getName(), cookie);
	}

	public static void setCookie(YokeRequest request, String key, String value) {
		YokeCookie cookie = new YokeCookie(key, CookieUtil.COOKIE_SIGNER);
		cookie.setValue(value);
		cookie.setPath("/");
		setCookie(request, cookie);
	}

	public static void discardCookie(YokeRequest request, String key) {
		DefaultCookie cookie = new DefaultCookie(key, "");
		cookie.setMaxAge(0);
		cookie.setPath("/");
		request.response().addCookie(cookie);

		Map<String, YokeCookie> additionalCookies = newCookies.get(request);//request.get("newCookies");
		if (additionalCookies == null) {
			additionalCookies = new HashMap<>();
			newCookies.put(request, additionalCookies);//request.put("newCookies", additionalCookies);
		}
		additionalCookies.put(key, null);
	}
}
