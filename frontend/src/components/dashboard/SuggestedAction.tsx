import { useState } from 'react';
import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query';
import { incidentService, type RecommendedAction } from '@/services/incident.service';
import { queryKeys } from '@/lib/queryKeys';
import { describeError } from '@/lib/errors';
import { formatRelative } from '@/lib/datetime';
import Button from '@/components/ui/Button';
import styles from './SuggestedAction.module.css';

export const ACTION_LABEL: Record<RecommendedAction, string> = {
    escalate: 'Escalate',
    dispatch: 'Dispatch a crew',
    verify_and_close: 'Verify the fix, then close',
    consolidate: 'Review with its hotspot',
    monitor: 'Monitor',
};

const ACTIONS = Object.keys(ACTION_LABEL) as RecommendedAction[];

/**
 * Decision support (Phase 11): the next step the rules recommend, the rule in
 * words, the facts it read — and the officer's answer. Accepting or overriding
 * only records the decision; status and department are still changed by hand.
 */
export default function SuggestedAction({ incidentId }: { incidentId: string }) {
    const queryClient = useQueryClient();
    const [overriding, setOverriding] = useState(false);
    const [chosen, setChosen] = useState<RecommendedAction | ''>('');
    const [note, setNote] = useState('');

    const query = useQuery({
        queryKey: queryKeys.incidents.recommendation(incidentId),
        queryFn: () => incidentService.recommendation(incidentId),
    });

    const feedback = useMutation({
        mutationFn: (body: Parameters<typeof incidentService.recommendationFeedback>[1]) =>
            incidentService.recommendationFeedback(incidentId, body),
        onSuccess: (data) => {
            queryClient.setQueryData(queryKeys.incidents.recommendation(incidentId), data);
            setOverriding(false);
            setChosen('');
            setNote('');
        },
    });

    if (query.isPending) return null;
    if (query.isError) {
        return (
            <section className={styles.box}>
                <h3 className={styles.heading}>Suggested next step</h3>
                <p className={styles.muted}>{describeError(query.error, 'the suggestion').title}</p>
            </section>
        );
    }

    const rec = query.data;
    const last = rec.lastFeedback;
    const answeredThis = last !== null && last.recommendedAction === rec.action;

    return (
        <section className={styles.box} aria-labelledby={`next-${incidentId}`}>
            <h3 className={styles.heading} id={`next-${incidentId}`}>
                Suggested next step
            </h3>

            {rec.action === null ? (
                <p className={styles.muted}>{rec.explanation}</p>
            ) : (
                <>
                    <p className={styles.action}>{ACTION_LABEL[rec.action]}</p>
                    <p className={styles.explanation}>{rec.explanation}</p>
                    <details className={styles.facts}>
                        <summary>What the rule read</summary>
                        <ul>
                            {rec.facts.map((f) => (
                                <li key={f}>{f}</li>
                            ))}
                        </ul>
                    </details>

                    {answeredThis && last ? (
                        <p className={styles.answered}>
                            {last.decision === 'accepted'
                                ? 'Accepted'
                                : `Overridden: ${last.chosenAction ? ACTION_LABEL[last.chosenAction] : ''}`}
                            {' · '}
                            {formatRelative(last.createdAt)}
                            {last.note ? ` — “${last.note}”` : ''}
                        </p>
                    ) : overriding ? (
                        <form
                            className={styles.override}
                            onSubmit={(e) => {
                                e.preventDefault();
                                if (!chosen) return;
                                feedback.mutate({
                                    decision: 'overridden',
                                    chosenAction: chosen,
                                    ...(note.trim() ? { note: note.trim() } : {}),
                                });
                            }}
                        >
                            <label className={styles.label}>
                                Instead
                                <select
                                    className={styles.select}
                                    value={chosen}
                                    onChange={(e) => setChosen(e.target.value as RecommendedAction | '')}
                                    required
                                >
                                    <option value="">Choose…</option>
                                    {ACTIONS.filter((a) => a !== rec.action).map((a) => (
                                        <option key={a} value={a}>
                                            {ACTION_LABEL[a]}
                                        </option>
                                    ))}
                                </select>
                            </label>
                            <label className={styles.label}>
                                Why (optional)
                                <textarea
                                    className={styles.note}
                                    rows={2}
                                    maxLength={1000}
                                    value={note}
                                    onChange={(e) => setNote(e.target.value)}
                                />
                            </label>
                            <div className={styles.buttons}>
                                <Button type="submit" size="compact" disabled={!chosen || feedback.isPending}>
                                    Save
                                </Button>
                                <Button
                                    type="button"
                                    variant="quiet"
                                    size="compact"
                                    onClick={() => setOverriding(false)}
                                >
                                    Cancel
                                </Button>
                            </div>
                        </form>
                    ) : (
                        <div className={styles.buttons}>
                            <Button
                                size="compact"
                                variant="secondary"
                                disabled={feedback.isPending}
                                onClick={() => feedback.mutate({ decision: 'accepted' })}
                            >
                                Accept
                            </Button>
                            <Button size="compact" variant="quiet" onClick={() => setOverriding(true)}>
                                Override…
                            </Button>
                        </div>
                    )}

                    {feedback.isError && (
                        <p className={styles.error}>{describeError(feedback.error, 'your decision').title}</p>
                    )}
                    <p className={styles.muted}>
                        Your answer is recorded to check the rules; it does not change the incident.
                    </p>
                </>
            )}
        </section>
    );
}
