package in.kevinj.lancamp.server.support;

import io.netty.handler.codec.http.QueryStringEncoder;

import java.io.IOException;
import java.net.MalformedURLException;
import java.net.URL;
import java.util.Map;

import org.vertx.java.core.Handler;
import org.vertx.java.core.Vertx;
import org.vertx.java.core.buffer.Buffer;
import org.vertx.java.core.http.HttpClient;
import org.vertx.java.core.http.HttpClientRequest;
import org.vertx.java.core.http.HttpClientResponse;
import org.vertx.java.core.http.HttpHeaders;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

public class WebServices {
	public static class ResponseInfo {
		public final int statusCode;
		public final String contentType;
		public final String body;

		public ResponseInfo(int statusCode, String contentType, String body) {
			this.statusCode = statusCode;
			this.contentType = contentType;
			this.body = body;
		}

		public boolean isJson() {
			return contentType.startsWith("application/json");
		}

		public JsonNode asJson() throws JsonProcessingException, IOException {
			return new ObjectMapper().readTree(body);
		}

		public boolean isQueryString() {
			return contentType.startsWith("application/x-www-form-urlencoded");
		}

		public Map<String, String[]> asQueryString() {
			throw new UnsupportedOperationException("Not implemented");
		}
	}

	public static void post(Vertx vertx, Map<String, String[]> parameters, String url, Handler<HttpClientResponse> handler) {
		try {
			URL urlObj = new URL(url);
			boolean isSsl = urlObj.getProtocol().equalsIgnoreCase("https");
			String host = urlObj.getHost();
			int port = urlObj.getPort();
			if (port == -1)
				if (isSsl)
					port = 443;
				else
					port = 80;
			String uri = urlObj.getPath() + urlObj.getQuery();

			HttpClient client = vertx.createHttpClient().setHost(host).setPort(port).setSSL(isSsl).setConnectTimeout(2000);
			QueryStringEncoder enc = new QueryStringEncoder("");
			for (Map.Entry<String, String[]> entry : parameters.entrySet())
				for (String value : entry.getValue())
					enc.addParam(entry.getKey(), value);
			String body = !parameters.isEmpty() ? enc.toString().substring(1) : "";
			HttpClientRequest req = client.post(uri, handler);
			req.headers().add(HttpHeaders.CONTENT_TYPE, "application/x-www-form-urlencoded; charset=utf-8");
			req.end(new Buffer(body));
		} catch (MalformedURLException e) {
			e.printStackTrace();
		}
	}

	private static void get(Vertx vertx, QueryStringEncoder enc, String url, Handler<String> failHandler, Handler<ResponseInfo> successHandler) {
		try {
			URL urlObj = new URL(url);
			boolean isSsl = urlObj.getProtocol().equalsIgnoreCase("https");
			String host = urlObj.getHost();
			int port = urlObj.getPort();
			if (port == -1)
				if (isSsl)
					port = 443;
				else
					port = 80;
			String uri = urlObj.getPath();

			HttpClient client = vertx.createHttpClient().setHost(host).setPort(port).setSSL(isSsl).setConnectTimeout(2000);
			String body = enc.toString();

			HttpClientRequest req = client.get(uri + body, resp -> {
				if (resp.statusCode() / 100 != 2) {
					failHandler.handle("HTTP " + resp.statusCode() + " " + resp.statusMessage());
					return;
				}
				resp.bodyHandler(buffer -> {
					String contType = resp.headers().get("Content-Type");
					if (contType != null && contType.contains("charset="))
						successHandler.handle(new ResponseInfo(resp.statusCode(), resp.headers().get("Content-Type"), buffer.toString(contType.substring(contType.indexOf("charset=") + "charset=".length()))));
					else
						successHandler.handle(new ResponseInfo(resp.statusCode(), resp.headers().get("Content-Type"), buffer.toString("UTF-8")));
				});
			});
			req.end();
		} catch (MalformedURLException e) {
			e.printStackTrace();
		}
	}

	public static void get(Vertx vertx, Map<String, String[]> parameters, String url, Handler<String> failHandler, Handler<ResponseInfo> successHandler) {
		QueryStringEncoder enc = new QueryStringEncoder("");
		for (Map.Entry<String, String[]> entry : parameters.entrySet())
			for (String value : entry.getValue())
				enc.addParam(entry.getKey(), value);
		get(vertx, enc, url, failHandler, successHandler);
	}

	public static void get(Vertx vertx, String url, Handler<String> failHandler, Handler<ResponseInfo> successHandler, String... parameters) {
		QueryStringEncoder enc = new QueryStringEncoder("");
		for (int i = 0; i < parameters.length; i += 2) {
			enc.addParam(parameters[i], parameters[i + 1]);
		}
		get(vertx, enc, url, failHandler, successHandler);
	}
}
