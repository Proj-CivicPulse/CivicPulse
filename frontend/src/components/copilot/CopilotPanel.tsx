import { useState, type FormEvent } from "react";
import {
  askCopilot,
  type CopilotApiError,
  type CopilotResponse,
  type CopilotScope,
  type CopilotSource,
} from "../../services/copilot.service";
import Button from "@/components/ui/Button";
import Field from "@/components/ui/Field";
import styles from "./CopilotPanel.module.css";

interface CopilotPanelProps {
  scope?: CopilotScope;
  onClose?: () => void;
  onOpenIncident?: (incidentId: string) => void;
  onNavigate?: (path: string) => void;
}

function getErrorMessage(error: unknown): string {
  const apiError = error as Partial<CopilotApiError>;

  if (apiError.code === "RATE_LIMITED") {
    const wait = apiError.retryAfterSeconds;

    return wait !== undefined
      ? `Too many requests. Please try again in ${wait} seconds.`
      : "Too many requests. Please wait before trying again.";
  }

  if (apiError.code === "COPILOT_UNAVAILABLE") {
    return "Copilot is currently unavailable. Please try again later.";
  }

  if (apiError.code === "COPILOT_BUSY") {
    return "Copilot is busy. Please try again shortly.";
  }

  if (apiError.message) {
    return apiError.message;
  }

  return "Something went wrong while contacting Copilot.";
}

/** Where a source leads; null for a metric, which has no page of its own. */
function sourcePath(source: CopilotSource): string | null {
  if (!source.targetId) return null;
  if (source.kind === "ward") return `/dashboard/wards/${source.targetId}`;
  if (source.kind === "category") return `/dashboard/categories/${source.targetId}`;
  // Hotspots are listed, with their members, on the Wards page.
  if (source.kind === "hotspot") return "/dashboard/wards";
  return null;
}

function SourceLink({
  source,
  onOpenIncident,
  onNavigate,
}: {
  source: CopilotSource;
  onOpenIncident?: (id: string) => void;
  onNavigate?: (path: string) => void;
}) {
  const path = sourcePath(source);
  const canOpen =
    (source.kind === "incident" && Boolean(source.targetId) && Boolean(onOpenIncident)) ||
    (path !== null && Boolean(onNavigate));

  function openSource() {
    if (source.kind === "incident" && source.targetId) {
      onOpenIncident?.(source.targetId);
    } else if (path) {
      onNavigate?.(path);
    }
  }

  return (
    <button
      type="button"
      className={styles.source}
      onClick={openSource}
      disabled={!canOpen}
      title={source.label}
    >
      <span className={styles.sourceKind}>{source.kind}</span>
      <span className={styles.sourceLabel}>{source.label}</span>
    </button>
  );
}

/** "Jnana Bharathi · Potholes · 2026-09-24 to 2026-10-24", or "All wards". */
function scopeLine(scope: CopilotResponse["scope"]): string {
  if (!scope) return "All wards";
  const parts = [scope.wardName ?? "All wards"];
  if (scope.categoryName) parts.push(scope.categoryName);
  if (scope.from || scope.to) {
    parts.push(`${scope.from?.slice(0, 10) ?? "start"} to ${scope.to?.slice(0, 10) ?? "now"}`);
  }
  return parts.join(" · ");
}

export default function CopilotPanel({
  scope,
  onClose,
  onOpenIncident,
  onNavigate,
}: CopilotPanelProps) {
  const [question, setQuestion] = useState("");
  const [result, setResult] = useState<CopilotResponse | null>(null);
  const [loading, setLoading] = useState(false);
  const [error, setError] = useState("");

  async function handleSubmit(event: FormEvent<HTMLFormElement>) {
    event.preventDefault();

    const trimmedQuestion = question.trim();
    if (!trimmedQuestion || loading) return;

    setLoading(true);
    setError("");
    setResult(null);

    try {
      const response = await askCopilot({
        question: trimmedQuestion,
        wardId: scope?.wardId,
        category: scope?.category,
        from: scope?.from,
        to: scope?.to,
      });

      setResult(response);
    } catch (err) {
      setError(getErrorMessage(err));
    } finally {
      setLoading(false);
    }
  }

  return (
    <aside className={styles.panel} aria-label="CivicPulse Copilot">
      <header className={styles.header}>
        <div>
          <h2 className={styles.title}>Copilot</h2>
          <p className={styles.intro}>
            Answers only from CivicPulse data in this view, and cites every claim.
          </p>
        </div>

        {onClose && (
          <Button variant="quiet" size="compact" onClick={onClose} aria-label="Close Copilot">
            ×
          </Button>
        )}
      </header>

      <form className={styles.form} onSubmit={handleSubmit}>
        <Field
          as="textarea"
          label="Your question"
          value={question}
          onChange={(event) => setQuestion(event.target.value)}
          placeholder="Which incidents need attention?"
          maxLength={1000}
          rows={3}
          required
        />

        <div>
          <Button type="submit" disabled={loading || !question.trim()}>
            {loading ? "Thinking…" : "Ask Copilot"}
          </Button>
        </div>
      </form>

      {loading && (
        <p className={styles.status} role="status">
          Reading the data in this view. Answers take a few seconds.
        </p>
      )}

      {error && (
        <div className={styles.error} role="alert">
          {error}
        </div>
      )}

      {result && (
        <section className={styles.result} aria-live="polite">
          {result.ungrounded && (
            <div className={styles.warning}>
              This answer cites nothing the data supports. Check it before acting on it.
            </div>
          )}

          {result.refused && (
            <div className={styles.warning}>
              Copilot could not provide the requested answer.
            </div>
          )}

          {!result.answerable && !result.refused && (
            <div className={styles.warning}>
              The data in this view cannot answer this question.
            </div>
          )}

          <h3 className={styles.heading}>Answer</h3>
          <p className={styles.answer}>{result.answer}</p>

          {result.droppedCitations > 0 && (
            <p className={styles.note}>
              {result.droppedCitations} unsupported citation(s) were removed.
            </p>
          )}

          {result.sources.length > 0 && (
            <div className={styles.sources}>
              <h4 className={styles.heading}>Sources</h4>

              <div className={styles.sourceList}>
                {result.sources.map((source) => (
                  <SourceLink
                    key={`${source.kind}-${source.id}`}
                    source={source}
                    onOpenIncident={onOpenIncident}
                    onNavigate={onNavigate}
                  />
                ))}
              </div>
            </div>
          )}

          <p className={styles.meta}>
            Answering for {scopeLine(result.scope)}
            {result.model ? ` · ${result.model}` : ""}
          </p>
        </section>
      )}
    </aside>
  );
}
