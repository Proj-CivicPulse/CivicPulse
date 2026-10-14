import { useEffect, useMemo, type ReactNode } from 'react';
import { CircleMarker, MapContainer, Marker, TileLayer, useMap, useMapEvents } from 'react-leaflet';
import L from 'leaflet';
import type { PriorityBand } from '@/lib/priority';
import { MAP_TILE_API_KEY } from '@/config/constants';
import 'leaflet/dist/leaflet.css';
import styles from './MapView.module.css';

/*
 * Keyless CARTO still serves tiles, but stamps every one with an "API KEY
 * REQUIRED" watermark. The key is appended only when configured so local dev
 * works with no setup at all.
 */
const TILE_URL = MAP_TILE_API_KEY
    ? `https://{s}.basemaps.cartocdn.com/light_all/{z}/{x}/{y}{r}.png?key=${encodeURIComponent(MAP_TILE_API_KEY)}`
    : 'https://{s}.basemaps.cartocdn.com/light_all/{z}/{x}/{y}{r}.png';

export interface MapMarker {
    id: string;
    lat: number;
    long: number;
    band: PriorityBand;
    /** Accessible description, also used as the marker tooltip. */
    label: string;
}

interface Props {
    markers?: readonly MapMarker[];
    selectedId?: string | null;
    onSelect?: (id: string) => void;
    /** Single draggable pin, for the submit flow's location step. */
    pin?: { lat: number; long: number } | null;
    onPinMove?: (lat: number, long: number) => void;
    center?: [number, number];
    zoom?: number;
    /** `inline` is the 320px submit-form map; `fill` expands to its container. */
    size?: 'inline' | 'fill';
    ariaLabel: string;
    /**
     * Extra layers drawn under the markers — the Explorer's ward shapes. They
     * render inside the MapContainer, so they can use react-leaflet hooks.
     */
    children?: ReactNode;
    /**
     * Frame the markers whenever the SET of markers changes (a new filter),
     * not when the selection does — so picking a row never yanks the view.
     */
    fitToMarkers?: boolean;
}

/** Bengaluru. Only used before geolocation resolves or when there is nothing to show. */
const FALLBACK_CENTER: [number, number] = [12.9716, 77.5946];

const BAND_CLASS: Record<PriorityBand, string> = {
    low: styles.markerLow ?? '',
    medium: styles.markerMedium ?? '',
    high: styles.markerHigh ?? '',
    critical: styles.markerCritical ?? '',
};

export default function MapView({
    markers = [],
    selectedId,
    onSelect,
    pin,
    onPinMove,
    center,
    zoom = 13,
    size = 'fill',
    ariaLabel,
    children,
    fitToMarkers = false,
}: Props) {
    const initialCenter = center ?? (pin ? [pin.lat, pin.long] : null) ?? FALLBACK_CENTER;

    return (
        <div className={size === 'inline' ? styles.inline : styles.fill} role="region" aria-label={ariaLabel}>
            <MapContainer
                center={initialCenter}
                zoom={zoom}
                className={styles.map}
                // The register's map is for locating things, not exploring. Box
                // zoom and double-click zoom just cause accidental jumps here.
                doubleClickZoom={false}
                boxZoom={false}
                // Quarter steps, so fitting a city or a ward frames it tightly
                // instead of snapping a whole zoom level out.
                zoomSnap={0.25}
                scrollWheelZoom
            >
                {/*
                 * CARTO Positron. Default OSM tiles are saturated enough to
                 * fight the priority ramp; on this basemap the markers carry
                 * the only real colour on screen.
                 *
                 * Both attributions are required and must stay.
                 */}
                <TileLayer
                    url={TILE_URL}
                    attribution='&copy; <a href="https://www.openstreetmap.org/copyright">OpenStreetMap</a> contributors &copy; <a href="https://carto.com/attributions">CARTO</a>'
                    subdomains="abcd"
                    maxZoom={20}
                />

                {children}

                {markers.map((marker) => {
                    const isSelected = marker.id === selectedId;
                    return (
                        <CircleMarker
                            key={marker.id}
                            center={[marker.lat, marker.long]}
                            radius={6}
                            pathOptions={{
                                className: `${styles.marker} ${BAND_CLASS[marker.band]}`,
                            }}
                            eventHandlers={{ click: () => onSelect?.(marker.id) }}
                        >
                            {isSelected && (
                                <CircleMarker
                                    center={[marker.lat, marker.long]}
                                    radius={10}
                                    interactive={false}
                                    pathOptions={{ className: styles.selectionRing }}
                                />
                            )}
                        </CircleMarker>
                    );
                })}

                {fitToMarkers && <FitMarkers markers={markers} />}

                {pin && <DraggablePin lat={pin.lat} long={pin.long} onMove={onPinMove} />}
                {pin && <Recenter lat={pin.lat} long={pin.long} />}
                {onPinMove && <ClickToPlace onPlace={onPinMove} />}
            </MapContainer>
        </div>
    );
}

/**
 * A divIcon, not Leaflet's default marker. The default icon resolves its PNGs
 * relative to leaflet.css, which Vite's asset hashing breaks — the classic
 * "marker is a broken image" bug. A div also lets the pin's colour stay in CSS.
 */
function DraggablePin({
    lat,
    long,
    onMove,
}: {
    lat: number;
    long: number;
    onMove?: (lat: number, long: number) => void;
}) {
    const icon = useMemo(
        () =>
            L.divIcon({
                className: styles.pinWrap,
                html: `<span class="${styles.pin}"></span>`,
                iconSize: [24, 24],
                iconAnchor: [12, 12],
            }),
        []
    );

    return (
        <Marker
            position={[lat, long]}
            icon={icon}
            draggable={onMove !== undefined}
            autoPan
            keyboard
            alt="Report location"
            eventHandlers={{
                dragend: (event) => {
                    const { lat: newLat, lng } = event.target.getLatLng();
                    onMove?.(newLat, lng);
                },
            }}
        />
    );
}

/**
 * Fits the view to the markers. Keyed on the ids, so it runs when the filtered
 * set changes and stays put while the officer pans or selects.
 */
function FitMarkers({ markers }: { markers: readonly MapMarker[] }) {
    const map = useMap();
    const ids = markers.map((m) => m.id).join(',');
    useEffect(() => {
        if (markers.length === 0) return;
        const bounds = L.latLngBounds(markers.map((m) => [m.lat, m.long] as [number, number]));
        map.fitBounds(bounds, { padding: [32, 32], maxZoom: 15 });
        // eslint-disable-next-line react-hooks/exhaustive-deps -- refit on a new set, not a new array
    }, [map, ids]);
    return null;
}

/** Tapping the map is faster than dragging on a phone. */
function ClickToPlace({ onPlace }: { onPlace: (lat: number, long: number) => void }) {
    useMapEvents({
        click: (event) => onPlace(event.latlng.lat, event.latlng.lng),
    });
    return null;
}

/** Follows the pin when it moves from outside the map, e.g. geolocation landing. */
function Recenter({ lat, long }: { lat: number; long: number }) {
    const map = useMap();
    useEffect(() => {
        map.panTo([lat, long]);
    }, [map, lat, long]);
    return null;
}
