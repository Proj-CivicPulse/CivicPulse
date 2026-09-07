package com.civicpulse.backend_spring.util;

/**
 * Great-circle distance between two WGS-84 points.
 *
 * One implementation, three callers: ward resolution, the priority engine's
 * geographic-spread term, and the incident grouper's distance gate.
 *
 * Haversine rather than squared-degree distance. Within a single city the two
 * rank almost identically, but squared degrees produces a number in no real
 * unit — and both the resolver's radius cut-off and the spread term need an
 * actual distance in metres to be meaningful.
 */
public final class GeoDistance {

    private static final double EARTH_RADIUS_METRES = 6_371_000.0;

    private GeoDistance() {
    }

    public static double metresBetween(double lat1, double lon1, double lat2, double lon2) {
        double dLat = Math.toRadians(lat2 - lat1);
        double dLon = Math.toRadians(lon2 - lon1);

        double a = Math.sin(dLat / 2) * Math.sin(dLat / 2)
                + Math.cos(Math.toRadians(lat1)) * Math.cos(Math.toRadians(lat2))
                * Math.sin(dLon / 2) * Math.sin(dLon / 2);

        return EARTH_RADIUS_METRES * 2 * Math.atan2(Math.sqrt(a), Math.sqrt(1 - a));
    }

    public static double kilometresBetween(double lat1, double lon1, double lat2, double lon2) {
        return metresBetween(lat1, lon1, lat2, lon2) / 1000.0;
    }
}
