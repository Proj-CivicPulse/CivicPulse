import { useEffect, useMemo } from 'react';
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
