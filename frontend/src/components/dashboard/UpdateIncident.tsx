import { useState } from 'react';
import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query';
import { incidentService, type Incident, type IncidentStatus } from '@/services/incident.service';
import { departmentService } from '@/services/department.service';
import { queryKeys } from '@/lib/queryKeys';
import { describeError } from '@/lib/errors';
import Button from '@/components/ui/Button';
import Field from '@/components/ui/Field';
import styles from './UpdateIncident.module.css';

const STATUS_LABEL: Record<IncidentStatus, string> = {
    open: 'Open',
    in_progress: 'In progress',
    resolved: 'Resolved — fixed',
    closed: 'Closed — ended without a fix',
};

type Hazard = 'suggested' | 'yes' | 'no';

const HAZARD_HELP: Record<Hazard, string> = {
    suggested: 'The reports mention a hazard. Your answer is final: later reports will not change it.',
    yes: 'Open drain or manhole, exposed wiring, sewage overflow, collapsed road, fallen tree or pole.',
    no: 'Not a hazard to life. Later reports will not raise it again.',
};

/** Where the select starts: an undecided text suggestion, or the decided value. */
function initialHazard(incident: Incident): Hazard {
    if (incident.lifeSafetySource === 'report_text' && incident.lifeSafety) return 'suggested';
    return incident.lifeSafety ? 'yes' : 'no';
}

const STATUS_HELP: Record<IncidentStatus, string> = {
    open: 'Reported, work not started.',
    in_progress: 'A crew or department is on it.',
    resolved: 'Fixed. Counts toward time-to-resolve.',
    closed: 'Ended without a fix (duplicate, out of scope). Not counted as resolved.',
};

/**
 * Where an officer acts on an incident: its status, owning department and
 * life-safety flag (PATCH /incidents/{id}). The suggestion box below only records agreement;
 * this is the control that changes the incident.
 *
 * "Resolved" and "Closed" are deliberately spelled out: the resolution
 * figures count resolved incidents only (data-quality-rules.md).
 */
export default function UpdateIncident({ incident }: { incident: Incident }) {
    const queryClient = useQueryClient();
    const [status, setStatus] = useState<IncidentStatus>(incident.status);
    const [departmentId, setDepartmentId] = useState<string>(incident.departmentId ?? '');
    const startHazard = initialHazard(incident);
    const [hazard, setHazard] = useState<Hazard>(startHazard);
    const [hazardNote, setHazardNote] = useState('');

    const departments = useQuery({
        queryKey: queryKeys.departments.list(),
        queryFn: () => departmentService.list(),
        staleTime: 60 * 60_000,
    });

    const update = useMutation({
        mutationFn: () =>
            incidentService.update(incident.id, {
                ...(status !== incident.status ? { status } : {}),
                ...(departmentId !== (incident.departmentId ?? '') && departmentId !== ''
                    ? { departmentId }
                    : {}),
                ...(hazard !== startHazard && hazard !== 'suggested'
                    ? {
                          lifeSafety: hazard === 'yes',
                          ...(hazard === 'yes' && hazardNote.trim() !== '' ? { lifeSafetyNote: hazardNote.trim() } : {}),
                      }
                    : {}),
            }),
        // The panel remounts with the saved values (it is keyed on them), and the
        // status chip changing is the confirmation.
        onSuccess: () => {
            // The queue, the Explorer figures and the suggestion all read this.
            void queryClient.invalidateQueries({ queryKey: queryKeys.incidents.all() });
            void queryClient.invalidateQueries({ queryKey: queryKeys.dashboard.all() });
        },
    });

    const changed =
        status !== incident.status ||
        (departmentId !== '' && departmentId !== (incident.departmentId ?? '')) ||
        (hazard !== startHazard && hazard !== 'suggested');

    return (
        <section className={styles.box} aria-labelledby={`update-${incident.id}`}>
            <h3 className={styles.heading} id={`update-${incident.id}`}>
                Update incident
            </h3>
            <form
                className={styles.form}
                onSubmit={(e) => {
                    e.preventDefault();
                    if (changed) update.mutate();
                }}
            >
                <Field
                    as="select"
                    label="Status"
                    helper={STATUS_HELP[status]}
                    value={status}
                    onChange={(e) => {
                        setStatus(e.target.value as IncidentStatus);
                    }}
                >
                    {(Object.keys(STATUS_LABEL) as IncidentStatus[]).map((s) => (
                        <option key={s} value={s}>
                            {STATUS_LABEL[s]}
                        </option>
                    ))}
                </Field>
                <Field
                    as="select"
                    label="Department"
                    helper={
                        incident.departmentId === null
                            ? "Unassigned: the category's default department owns it until you choose one."
                            : undefined
                    }
                    value={departmentId}
                    onChange={(e) => {
                        setDepartmentId(e.target.value);
                    }}
                    disabled={departments.isPending}
                >
                    <option value="">{incident.departmentId === null ? 'Category default' : 'Choose…'}</option>
                    {(departments.data ?? []).map((d) => (
                        <option key={d.id} value={d.id}>
                            {d.name}
                        </option>
                    ))}
                </Field>
                <Field
                    as="select"
                    label="Life-safety hazard"
                    helper={HAZARD_HELP[hazard]}
                    value={hazard}
                    onChange={(e) => {
                        setHazard(e.target.value as Hazard);
                    }}
                >
                    {startHazard === 'suggested' && <option value="suggested">Suggested by the reports — decide</option>}
                    <option value="yes">Yes — a hazard to life</option>
                    <option value="no">No</option>
                </Field>
                {hazard === 'yes' && startHazard !== 'yes' && (
                    <Field
                        label="What you saw (optional)"
                        value={hazardNote}
                        maxLength={240}
                        onChange={(e) => {
                            setHazardNote(e.target.value);
                        }}
                    />
                )}
                <div className={styles.buttons}>
                    <Button type="submit" size="compact" disabled={!changed || update.isPending}>
                        {update.isPending ? 'Saving…' : 'Save changes'}
                    </Button>
                </div>
                {update.isError && (
                    <p className={styles.error}>{describeError(update.error, 'the incident').title}</p>
                )}
            </form>
        </section>
    );
}
