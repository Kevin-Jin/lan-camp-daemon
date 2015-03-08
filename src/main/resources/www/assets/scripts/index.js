function replaceStaticMapWithDynamicMap() {
	var element = $('#map');

	element.css('background', 'black');
	var coords = markers[0].split(',');
	var latlon = new google.maps.LatLng(coords[0], coords[1]);
	var map = new google.maps.Map(element[0], {
		center: latlon,
		zoom: 14,
		mapTypeId: google.maps.MapTypeId.ROADMAP,
		mapTypeControl: false,
		navigationControlOptions: {
			style: google.maps.NavigationControlStyle.SMALL
		}
	});
	for (var i = 0; i < markers.length; i++) {
		coords = markers[i].split(',');
		latlon = new google.maps.LatLng(coords[0], coords[1]);
		var infowindow = new google.maps.InfoWindow();
		var marker = new google.maps.Marker({ position: latlon, map: map });
		google.maps.event.addListener(marker, 'click', (function(infowindow, marker, i) {
			infowindow.setContent(stats[i]);
			infowindow.open(map, marker);
		})(infowindow, marker, i));
	}
}

$(document).on('ready', function() {
	replaceStaticMapWithDynamicMap();
});
