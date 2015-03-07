package in.kevinj.lancamp.server.support;

import java.util.UUID;

import com.jetdrone.vertx.yoke.middleware.YokeRequest;

public class CsrfUtil {
	public static String generateToken(YokeRequest request, String cookieKey) {
		String formUuid = UUID.randomUUID().toString();
		CookieUtil.setCookie(request, cookieKey, UserAuth.sign(formUuid));
		//disable browser caching of the form page because value of uuid input changes every page load
		request.response().putHeader("Cache-Control", "no-cache, max-age=0, must-revalidate, no-store");
		return formUuid;		
	}

	public static String generateToken(YokeRequest request) {
		return generateToken(request, "csrf_token");
	}

	public static boolean verifyToken(YokeRequest request, String formToken, String cookieKey) {
		//don't discard csrf_token cookie because token should still be valid
		//if user refreshes page by accident
		String cookieToken = CookieUtil.getCookie(request, cookieKey);
		return UserAuth.matchSign(cookieToken, formToken);
	}

	public static boolean verifyToken(YokeRequest request, String formToken) {
		return verifyToken(request, formToken, "csrf_token");
	}
}
