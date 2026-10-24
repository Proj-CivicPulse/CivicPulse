import { useId, useState } from 'react';
import { useNavigate } from 'react-router-dom';
import Button from '@/components/ui/Button';
import { useDashboardStore } from '@/stores/dashboard.store';
import { reportWindow, type WindowDays } from '@/lib/reportWindow';
import type { CopilotScope } from '@/services/copilot.service';
import CopilotPanel from './CopilotPanel';
import styles from './CopilotSection.module.css';

/**
 * The Explorer's shared filters as the Copilot's scope, so an answer covers
 * exactly what the officer is looking at.
 */
export function copilotScope(
    wardId: string | null,
    category: string | null,
    windowDays: WindowDays | null,
): CopilotScope {
    return {
        ...(wardId !== null ? { wardId } : {}),
        ...(category !== null ? { category } : {}),
        ...reportWindow(windowDays),
    };
}

/**
 * "Ask Copilot" and its panel, for the Explorer pages that are not the queue.
 * A cited incident opens in the queue, with every filter cleared so it is
 * certain to be in the list it opens in.
 */
export default function CopilotSection({ scope }: { scope: CopilotScope }) {
    const [open, setOpen] = useState(false);
    const panelId = useId();
    const navigate = useNavigate();
    const clearFilters = useDashboardStore((s) => s.clearFilters);
    const selectWard = useDashboardStore((s) => s.selectWard);
    const selectIncident = useDashboardStore((s) => s.selectIncident);

    const openIncident = (incidentId: string) => {
        clearFilters();
        selectWard(null);
        selectIncident(incidentId);
        navigate('/dashboard');
    };

    return (
        <section className={styles.section} aria-label="Copilot">
            <div>
                <Button
                    variant="secondary"
                    size="compact"
                    onClick={() => setOpen((o) => !o)}
                    aria-expanded={open}
                    aria-controls={panelId}
                >
                    {open ? 'Close Copilot' : 'Ask Copilot'}
                </Button>
            </div>
            {open && (
                <div id={panelId}>
                    <CopilotPanel
                        scope={scope}
                        onClose={() => setOpen(false)}
                        onOpenIncident={openIncident}
                        onNavigate={navigate}
                    />
                </div>
            )}
        </section>
    );
}
