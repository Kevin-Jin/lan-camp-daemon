package in.kevinj.lancamp.server;

import in.kevinj.lancamp.server.controller.AuthenticationController;
import in.kevinj.lancamp.server.controller.IndexController;
import in.kevinj.lancamp.server.support.CookieUtil;
import in.kevinj.lancamp.server.support.UserAuth;
import in.kevinj.lancamp.server.templating.AssetHandler;
import in.kevinj.lancamp.server.templating.ClosureTemplateEngine;
import in.kevinj.lancamp.server.templating.YokeMarkupTemplateEngine;
import io.netty.handler.codec.http.Cookie;
import io.netty.handler.codec.http.CookieDecoder;

import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;

import org.vertx.java.core.Handler;
import org.vertx.java.platform.Verticle;

import com.jetdrone.vertx.yoke.Middleware;
import com.jetdrone.vertx.yoke.Yoke;
import com.jetdrone.vertx.yoke.core.YokeCookie;
import com.jetdrone.vertx.yoke.middleware.BodyParser;
import com.jetdrone.vertx.yoke.middleware.CookieParser;
import com.jetdrone.vertx.yoke.middleware.ErrorHandler;
import com.jetdrone.vertx.yoke.middleware.Logger;
import com.jetdrone.vertx.yoke.middleware.Router;
import com.jetdrone.vertx.yoke.middleware.YokeRequest;

import dk.brics.automaton.BasicOperations;
import dk.brics.automaton.RegExp;

public class WebRouter extends Verticle {
	public enum Routes {
		IndexController$index						("GET",		"/"),
		IndexController$logoff						("GET",		"/user/unbind"),
		AuthenticationController$login				("GET",		"/buyer/user/bind")
		;

		public static final String WEB_ROOT = "";
		public static final Map<String, List<String>> forSoy;

		private static final String VALID_CAPTURE_GROUP = "[A-Za-z][A-Za-z0-9_]*";
		private static final Pattern VALID_CAPTURE_GROUP_PATTERN = Pattern.compile(VALID_CAPTURE_GROUP);

		static {
			Map<String, List<String>> collection = new HashMap<>();
			for (Routes page : Routes.values())
				collection.put(page.name(), Arrays.asList(page.verb.toLowerCase(), WEB_ROOT + page.path()));
			forSoy = Collections.unmodifiableMap(collection);
		}

		public final String verb;
		private final String pathPattern;

		private Routes(String verb, String path) {
			this.verb = verb;
			//turn any standard regex named capture groups into Yoke's :<token name> form
			//this is to simplify path(), so that we make only a single call to
			//replaceAll and so that Automaton doesn't trip (the library doesn't
			//support regex capture groups).
			this.pathPattern = path.replaceAll(Pattern.quote("(?<") + "(" + VALID_CAPTURE_GROUP + ")" + Pattern.quote(">[^/]+)"), ":$1");
		}

		/**
		 * Find shortest match to pathPattern in which certain values must be in the capturing groups.
		 * @param pathParams the values for the capturing groups
		 * @return a URL path
		 */
		public String path(String... pathParams) {
			String transformed = pathPattern;

			//replace the named capture groups with the passed values
			for (int i = 0; i + 1 < pathParams.length; i += 2) {
				String captureGroup = pathParams[i];
				String replacement = pathParams[i + 1];
				if (!VALID_CAPTURE_GROUP_PATTERN.matcher(captureGroup).matches())
					continue;

				transformed = transformed.replaceAll(":" + captureGroup + "(?![A-Za-z0-9_])", replacement);
			}

			//find the shortest match to the regex
			transformed = BasicOperations.getShortestExample(new RegExp(transformed).toAutomaton(), true);

			return transformed;
		}
	}

	private static final int PORT = 9000;

	@Override
	public void start() {
		String instanceId = container.config().getString("instanceId");

		IndexController c0 = new IndexController(vertx, container);
		AuthenticationController c1 = new AuthenticationController(vertx, container);
		//IndividualBuyerController c2 = new IndividualBuyerController(vertx, container);
		//IndividualSellerController c3 = new IndividualSellerController(vertx, container);
		Yoke yoke = new Yoke(this);
		yoke.engine(new ClosureTemplateEngine(container.logger(), "./www/views"));
		yoke.engine(new YokeMarkupTemplateEngine(container.logger(), "./www/views"));
		yoke.use(new ErrorHandler(true));
		yoke.use(new Logger());
		yoke.use(new CookieParser(CookieUtil.COOKIE_SIGNER) {
			@Override
			public void handle(final YokeRequest request, final Handler<Object> next) {
				boolean invalid = false;
				String cookieHeader = request.getHeader("cookie");
				if (cookieHeader != null) {
					for (Cookie cookie : CookieDecoder.decode(cookieHeader)) {
						if (new YokeCookie(cookie, CookieUtil.COOKIE_SIGNER).getUnsignedValue() == null) {
							CookieUtil.discardCookie(request, cookie.getName());
							invalid = true;
						}
					}
				}
				if (invalid)
					next.handle(400);
				else
					super.handle(request, next);
			}
		});
		yoke.use(new BodyParser());
		yoke.use("/assets", new AssetHandler(vertx, container, instanceId, "./www/assets", "./compiled/assets"));
		yoke.use(new Middleware() {
			@Override
			public void handle(final YokeRequest request, final Handler<Object> next) {
				UserAuth.validateSessionCookie(vertx, container, request, error1 -> {
					UserAuth.loadRememberedClient(vertx, container, request, error2 -> {
						request.put("user", UserAuth.getCurrentUserId(request));
						next.handle(null);
					});
				});
			}
		});
		yoke.use(new Router()
			.get(Routes.IndexController$index.pathPattern, c0::index)
			.get(Routes.AuthenticationController$login.pathPattern, c1::loginPage)
			.post(Routes.AuthenticationController$login.pathPattern, c1::login)
			.all(Routes.IndexController$logoff.pathPattern, c0::logoff)
		);
		yoke.listen(PORT, "0.0.0.0");
	}
}
