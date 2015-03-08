package in.kevinj.lancamp.server.support;

import org.vertx.java.core.Handler;
import org.vertx.java.core.Vertx;
import org.vertx.java.platform.Container;

import com.fasterxml.jackson.databind.JsonNode;
import com.jetdrone.vertx.yoke.middleware.YokeRequest;

public class DetailedLocation {
	public final String street, town, state, zip, lat, lng;
	public final String formattedAddress;

	public DetailedLocation(String street, String town, String state, String zip, String lat, String lng, String formattedAddress) {
		this.street = street;
		this.town = town;
		this.state = state;
		this.zip = zip;
		this.lat = lat;
		this.lng = lng;
		this.formattedAddress = formattedAddress;
	}

	public DetailedLocation(String town, String state, String zip, String lat, String lng, String formattedAddress) {
		this(null, town, state, zip, lat, lng, formattedAddress);
	}

	public boolean zipNotFound() {
		return town == null && state == null && zip == null && lat == null && lng == null;
	}

	public boolean notInCountry() {
		return town == null && state == null && zip != null && lat != null && lng != null;
	}

	public static void getByAddress(Vertx vertx, Container container, final String address, Handler<DetailedLocation> handler) {
		WebServices.get(vertx, "https://maps.googleapis.com/maps/api/geocode/json", error -> {
			container.logger().warn("Error querying Google Maps API: " + error);
			handler.handle(null);
		}, (Handler<WebServices.ResponseInfo>) resp -> {
			try {
				String streetNum = null, route = null, town = null, state = null, country = null, zip = null;
				String formattedAddress = null, lat = null, lng = null;
				
				JsonNode root = resp.asJson();
				results: for (JsonNode result : root.path("results")) {
					for (JsonNode address_component : result.path("address_components")) {
						for (JsonNode componentType : address_component.path("types")) {
							if (componentType.asText().equals("street_number"))
								streetNum = address_component.path("short_name").asText();
							else if (componentType.asText().equals("route"))
								route = address_component.path("short_name").asText();
							else if (componentType.asText().equals("country"))
								country = address_component.path("short_name").asText();
							else if (componentType.asText().equals("administrative_area_level_1"))
								state = address_component.path("short_name").asText();
							else if (componentType.asText().equals("locality"))
								town = address_component.path("long_name").asText();
							else if (componentType.asText().equals("postal_code"))
								zip = address_component.path("long_name").asText(); 
						}
					}
					formattedAddress = result.path("formatted_address").asText();
					lat = result.path("geometry").path("location").path("lat").asText();
					lng = result.path("geometry").path("location").path("lng").asText();
					if (country.equals("US"))
						break results;
				}
				if (town == null || state == null || country == null || zip == null) {
					//location not found
					handler.handle(new DetailedLocation(streetNum + ' ' + route, null, null, null, null, null, formattedAddress));
				} else {
					if (!country.equals("US"))
						//only locations inside the US are supported at this time
						handler.handle(new DetailedLocation(streetNum + ' ' + route, null, null, zip, lat, lng, formattedAddress));
					else
						handler.handle(new DetailedLocation(streetNum + ' ' + route, town, state, zip, lat, lng, formattedAddress));
				}
			} catch (Throwable t) {
				container.logger().warn("Error querying Google Maps API", t);
				handler.handle(null);
			}
		}, "address", address, "sensor", "false");
	}

	public static void get(Vertx vertx, Container container, final String lat, final String lng, Handler<DetailedLocation> handler) {
		WebServices.get(vertx, "https://maps.googleapis.com/maps/api/geocode/json", error -> {
			container.logger().warn("Error querying Google Maps API: " + error);
			handler.handle(null);
		}, (Handler<WebServices.ResponseInfo>) resp -> {
			try {
				String town = null, state = null, country = null, zip = null;
				String formattedAddress = null;
				JsonNode root = resp.asJson();
				results: for (JsonNode result : root.path("results")) {
					for (JsonNode address_component : result.path("address_components")) {
						for (JsonNode componentType : address_component.path("types")) {
							if (componentType.asText().equals("country"))
								country = address_component.path("short_name").asText();
							else if (componentType.asText().equals("administrative_area_level_1"))
								state = address_component.path("short_name").asText();
							else if (componentType.asText().equals("locality"))
								town = address_component.path("long_name").asText();
							else if (componentType.asText().equals("postal_code"))
								zip = address_component.path("long_name").asText(); 
						}
					}
					formattedAddress = result.path("formatted_address").asText();
					if (country.equals("US"))
						break results;
				}
				if (town == null || state == null || country == null || zip == null) {
					//location not found
					handler.handle(new DetailedLocation(null, null, null, null, null, formattedAddress));
				} else {
					if (!country.equals("US"))
						//only locations inside the US are supported at this time
						handler.handle(new DetailedLocation(null, null, zip, lat, lng, formattedAddress));
					else
						handler.handle(new DetailedLocation(town, state, zip, lat, lng, formattedAddress));
				}
			} catch (Throwable t) {
				container.logger().warn("Error querying Google Maps API", t);
				handler.handle(null);
			}
		}, "latlng", lat + "," + lng, "sensor", "false");
	}

	public static void get(Vertx vertx, Container container, String zip, Handler<DetailedLocation> handler) {
		zip = zip.trim();
		final String queryZip; //5 digit zip
		if (zip.length() == 5) {
			try {
				Integer.parseInt(zip);
				queryZip = zip;
			} catch (NumberFormatException e) {
				//invalid ZIP code
				handler.handle(new DetailedLocation(null, null, null, null, null, null));
				return;
			}
		} else if (zip.length() == 10) { //zip + 4
			try {
				Integer.parseInt(zip.substring(6, 10));
				Integer.parseInt(queryZip = zip.substring(0, 5));
			} catch (NumberFormatException e) {
				//invalid ZIP code
				handler.handle(new DetailedLocation(null, null, null, null, null, null));
				return;
			}
		} else {
			//invalid ZIP code
			handler.handle(new DetailedLocation(null, null, null, null, null, null));
			return;
		}
		WebServices.get(vertx, "https://maps.googleapis.com/maps/api/geocode/json", error -> {
			container.logger().warn("Error querying Google Maps API: " + error);
			handler.handle(null);
		}, (Handler<WebServices.ResponseInfo>) resp -> {
			try {
				String town = null, state = null, country = null, zipNew = null;
				JsonNode position = null;
				JsonNode root = resp.asJson();
				results: for (JsonNode result : root.path("results")) {
					for (JsonNode address_component : result.path("address_components")) {
						for (JsonNode componentType : address_component.path("types")) {
							if (componentType.asText().equals("country"))
								country = address_component.path("short_name").asText();
							else if (componentType.asText().equals("administrative_area_level_1"))
								state = address_component.path("short_name").asText();
							else if (componentType.asText().equals("locality"))
								town = address_component.path("long_name").asText();
							else if (componentType.asText().equals("postal_code"))
								zipNew = address_component.path("long_name").asText(); 
						}
					}
					//we only want to search by ZIP code. skip over any results
					//if we matched something other than a US zip code
					//Google API is buggy and sometimes won't give us postal_code
					//even if the address was a ZIP code...
					if (zipNew != null && !queryZip.equals(zipNew))
						continue results;
					position = result;
					if (country.equals("US"))
						break results;
				}
				if (position == null) {
					//location not found
					handler.handle(new DetailedLocation(null, null, null, null, null, null));
				} else {
					JsonNode location = position.path("geometry").path("location");
					if (!country.equals("US"))
						//only locations inside the US are supported at this time
						handler.handle(new DetailedLocation(null, null, queryZip, location.path("lat").asText(), location.path("lng").asText(), position.path("formatted_address").asText()));
					else
						handler.handle(new DetailedLocation(town, state, queryZip, location.path("lat").asText(), location.path("lng").asText(), position.path("formatted_address").asText()));
				}
			} catch (Throwable t) {
				container.logger().warn("Error querying Google Maps API", t);
				handler.handle(null);
			}
		}, "address", zip, "sensor", "false");
	}

	public static String getLocationName(YokeRequest req) {
		String location = CookieUtil.getCookie(req, "loc");
		if (location != null && !location.isEmpty())
			return location.substring(location.indexOf('(') + 1, location.lastIndexOf(')'));
		return "";
	}

	public static String getLocationCoordinates(YokeRequest req) {
		String location = CookieUtil.getCookie(req, "loc");
		if (location != null && !location.isEmpty())
			return location.substring(0, location.indexOf('('));
		return "";
	}
}
