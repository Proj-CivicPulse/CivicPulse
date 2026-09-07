import { useEffect, useState } from 'react';
import { Check, Copy } from 'lucide-react';
import { useMutation, useQuery } from '@tanstack/react-query';
import { complaintService } from '@/services/complaint.service';
import { wardService } from '@/services/ward.service';
import { queryKeys } from '@/lib/queryKeys';
import { errorMessage } from '@/lib/errors';
import { formatCoordinates, truncate } from '@/lib/format';
import AppHeader from '@/components/AppHeader';
import Button from '@/components/ui/Button';
import Field from '@/components/ui/Field';
import MapView from '@/components/ui/MapView';
import styles from './SubmitComplaint.module.css';

/**
 * Client-side list. There is no categories endpoint in the contract, and
 * `complaints.category` is a free-form VARCHAR, so this is the frontend's own
 * vocabulary until the backend owns one. Values are what the server stores;
 * labels are what a resident would say.
 */
const CATEGORIES = [
    { value: 'pothole', label: 'Pothole or damaged road' },
    { value: 'streetlight', label: 'Street light out' },
    { value: 'garbage', label: 'Garbage not collected' },
    { value: 'water', label: 'Water supply or leak' },
    { value: 'drainage', label: 'Blocked drain or waterlogging' },
    { value: 'other', label: 'Something else' },
] as const;

const DESCRIPTION_MAX = 5000;
const FALLBACK: [number, number] = [12.9716, 77.5946];

export default function SubmitComplaint() {
    const [category, setCategory] = useState('');
    const [description, setDescription] = useState('');
    // Starts on the city centre rather than null. Geolocation is a convenience,
    // not a gate: a resident who denies the prompt still gets a usable map to
    // drag from, and `locating` only gates the "finding you" message.
    const [pin, setPin] = useState({ lat: FALLBACK[0], long: FALLBACK[1] });
    const [locating, setLocating] = useState(() => Boolean(navigator.geolocation));
    const [manualWardId, setManualWardId] = useState('');
    const [showErrors, setShowErrors] = useState(false);
    const [copied, setCopied] = useState(false);

    useEffect(() => {
        if (!navigator.geolocation) return;

        let cancelled = false;
        navigator.geolocation.getCurrentPosition(
            (position) => {
                if (cancelled) return;
                setPin({ lat: position.coords.latitude, long: position.coords.longitude });
                setLocating(false);
            },
            () => {
                // Denied or unavailable. Keep the fallback pin already in state.
                if (cancelled) return;
                setLocating(false);
            },
            { enableHighAccuracy: true, timeout: 8000 }
        );

        return () => {
            cancelled = true;
        };
    }, []);

    // The system knowing your ward from the pin is the trust moment. Showing it
    // is the point — this is never a dropdown the resident has to fill in.
    const wardQuery = useQuery({
        queryKey: queryKeys.wards.resolve(pin.lat, pin.long),
        queryFn: () => wardService.resolve(pin.lat, pin.long),
        // A 404 here is a real answer ("no ward covers that point"), not a
        // transient failure, so retrying it just delays the manual picker.
        retry: false,
        staleTime: 5 * 60_000,
    });

    // Only needed when resolution fails (a pin outside every ward), so it stays
    // unfetched on the happy path.
    const wardsQuery = useQuery({
        queryKey: queryKeys.wards.list(),
        queryFn: () => wardService.list(),
        enabled: wardQuery.isError,
        staleTime: 5 * 60_000,
    });

    const submission = useMutation({
        mutationFn: complaintService.create,
    });

    const resolvedWardId = wardQuery.data?.id ?? (manualWardId || null);
    const categoryError = showErrors && !category ? 'Choose what kind of problem this is.' : undefined;
    const descriptionError =
        showErrors && description.trim().length === 0 ? 'Tell us what happened.' : undefined;

    function onSubmit(event: React.FormEvent<HTMLFormElement>) {
        event.preventDefault();
        setShowErrors(true);
        if (!category || description.trim().length === 0 || !resolvedWardId) return;

        submission.mutate({
            category,
            description: description.trim(),
            lat: pin.lat,
            long: pin.long,
            wardId: resolvedWardId,
        });
    }

    async function copyReference(reference: string) {
        try {
            await navigator.clipboard.writeText(reference);
            setCopied(true);
            window.setTimeout(() => setCopied(false), 2000);
        } catch {
            // Clipboard access can be denied outright; the number is on screen
            // and selectable either way, so this needs no error state.
        }
    }

    /* ------------------------------------------------------ confirmation */

    if (submission.isSuccess) {
        const complaint = submission.data;
        return (
            <div className={styles.page}>
                <AppHeader />
                <main className={styles.main}>
                    <div className={styles.column}>
                        <h1 className={styles.title}>Report submitted</h1>
                        <p className={styles.lede}>
                            Keep this reference number. It&rsquo;s how you look the report up later.
                        </p>

                        <div className={styles.receipt}>
                            <span className={styles.receiptLabel}>Reference</span>
                            <div className={styles.receiptRow}>
                                <span className={styles.reference}>{complaint.referenceNo}</span>
                                <Button
                                    variant="secondary"
                                    size="compact"
                                    onClick={() => void copyReference(complaint.referenceNo)}
                                >
                                    {copied ? (
                                        <>
                                            <Check size={14} strokeWidth={2} aria-hidden="true" />
                                            Copied
                                        </>
                                    ) : (
                                        <>
                                            <Copy size={14} strokeWidth={2} aria-hidden="true" />
                                            Copy
                                        </>
                                    )}
                                </Button>
                            </div>
                        </div>

                        <div className={styles.confirmActions}>
                            <Button to="/complaints" variant="primary">
                                Track my reports
                            </Button>
                            <Button to="/" variant="quiet">
                                Back to home
                            </Button>
                        </div>
                    </div>
                </main>
            </div>
        );
    }

    /* --------------------------------------------------------------- form */

    return (
        <div className={styles.page}>
            <AppHeader />

            <main className={styles.main}>
                <form className={styles.column} onSubmit={onSubmit} noValidate>
                    <h1 className={styles.title}>Report a problem</h1>
                    <p className={styles.lede}>
                        This takes about a minute. You don&rsquo;t need an account.
                    </p>

                    {submission.isError && (
                        <p className={styles.formError} role="alert">
                            {errorMessage(submission.error)}
                        </p>
                    )}

                    {/* One question per block, separated by a rule. */}
                    <section className={styles.block}>
                        <h2 className={styles.blockTitle}>What happened?</h2>
                        <Field
                            as="select"
                            label="Kind of problem"
                            value={category}
                            error={categoryError}
                            onChange={(e) => setCategory(e.target.value)}
                        >
                            <option value="">Choose one…</option>
                            {CATEGORIES.map((option) => (
                                <option key={option.value} value={option.value}>
                                    {option.label}
                                </option>
                            ))}
                        </Field>

                        <Field
                            as="textarea"
                            label="Describe it"
                            className={styles.spaced}
                            value={description}
                            maxLength={DESCRIPTION_MAX}
                            error={descriptionError}
                            helper="What is wrong, and roughly how long it has been like that."
                            aside={`${description.length}/${DESCRIPTION_MAX}`}
                            onChange={(e) => setDescription(e.target.value)}
                        />
                    </section>

                    <section className={styles.block}>
                        <h2 className={styles.blockTitle}>Where is it?</h2>
                        <p className={styles.blockHint}>
                            Drag the pin, or tap the map, to place it exactly.
                        </p>

                        {locating && (
                            <p className={styles.locating} role="status">
                                Finding your location…
                            </p>
                        )}

                        <MapView
                            size="inline"
                            ariaLabel="Choose the location of the problem"
                            pin={pin}
                            onPinMove={(lat, long) => setPin({ lat, long })}
                            zoom={16}
                        />

                        <dl className={styles.derived}>
                            <div className={styles.derivedRow}>
                                <dt className={styles.derivedLabel}>Coordinates</dt>
                                <dd className={styles.derivedValue}>
                                    {formatCoordinates(pin.lat, pin.long)}
                                </dd>
                            </div>
                            <div className={styles.derivedRow}>
                                <dt className={styles.derivedLabel}>Ward</dt>
                                <dd className={styles.derivedValue}>
                                    {wardQuery.isPending
                                        ? 'Working it out…'
                                        : wardQuery.data
                                          ? wardQuery.data.name
                                          : 'Not recognised'}
                                </dd>
                            </div>
                        </dl>

                        {/*
                         * Resolution failed — the pin is outside every ward we
                         * know. Rather than silently filing it into the nearest
                         * one, ask.
                         */}
                        {wardQuery.isError && (
                            <Field
                                as="select"
                                label="Ward"
                                className={styles.spaced}
                                value={manualWardId}
                                helper="We couldn’t work out the ward from that location. Pick the right one."
                                error={showErrors && !manualWardId ? 'Choose a ward.' : undefined}
                                onChange={(e) => setManualWardId(e.target.value)}
                            >
                                <option value="">Choose one…</option>
                                {(wardsQuery.data ?? []).map((ward) => (
                                    <option key={ward.id} value={ward.id}>
                                        {ward.name}
                                    </option>
                                ))}
                            </Field>
                        )}
                    </section>

                    <section className={styles.block}>
                        <h2 className={styles.blockTitle}>Photo</h2>
                        {/*
                         * Deliberately not built. docs/endpoints.md still has the
                         * upload mechanism open (direct-to-storage URL vs. a
                         * multipart endpoint), and no storage provider is
                         * configured — so a working-looking uploader here would
                         * be a lie. Saying so is better than a dead button.
                         */}
                        <p className={styles.blockHint}>
                            Photo upload isn&rsquo;t available yet. Describe what you see and a ward
                            officer will follow up.
                        </p>
                    </section>

                    <section className={styles.block}>
                        <h2 className={styles.blockTitle}>Review</h2>
                        <dl className={styles.derived}>
                            <div className={styles.derivedRow}>
                                <dt className={styles.derivedLabel}>Problem</dt>
                                <dd className={styles.derivedValue}>
                                    {CATEGORIES.find((c) => c.value === category)?.label ?? '—'}
                                </dd>
                            </div>
                            <div className={styles.derivedRow}>
                                <dt className={styles.derivedLabel}>Description</dt>
                                <dd className={styles.derivedValue}>
                                    {description.trim() ? truncate(description, 80) : '—'}
                                </dd>
                            </div>
                        </dl>

                        <div className={styles.submitRow}>
                            <Button type="submit" disabled={submission.isPending}>
                                {submission.isPending ? 'Submitting…' : 'Submit report'}
                            </Button>
                        </div>
                    </section>
                </form>
            </main>
        </div>
    );
}
