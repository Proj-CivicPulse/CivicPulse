import { useState, type FormEvent } from "react";
import {
  askCopilot,
  type CopilotApiError,
  type CopilotResponse,
  type CopilotScope,
  type CopilotSource,
} from "../../services/copilot.service";
import "./CopilotPanel.css";

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

function SourceLink({
  source,
  onOpenIncident,
  onNavigate,
}: {
  source: CopilotSource;
  onOpenIncident?: (id: string) => void;
  onNavigate?: (path: string) => void;
}) {
  function openSource() {
    if (!source.targetId) return;

    if (source.kind === "incident") {
      onOpenIncident?.(source.targetId);
    } else if (source.kind === "ward") {
      onNavigate?.(`/dashboard/wards/${source.targetId}`);
    } else if (source.kind === "category") {
      onNavigate?.(`/dashboard/categories/${source.targetId}`);
    }
  }

  const canOpen =
    Boolean(source.targetId) &&
    (source.kind === "incident" ||
      source.kind === "ward" ||
      source.kind === "category");

  return (
    <button
      type="button"
      className="copilot-source"
      onClick={openSource}
      disabled={!canOpen}
      title={source.label}
    >
      <span className="copilot-source-kind">{source.kind}</span>
      <span>{source.label}</span>
    </button>
  );
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
    <aside className="copilot-panel" aria-label="CivicPulse Copilot">
      <header className="copilot-header">
        <div>
          <h2>CivicPulse Copilot</h2>
          <p>Ask questions about incidents in the selected scope.</p>
        </div>

        {onClose && (
          <button
            type="button"
            className="copilot-close"
            onClick={onClose}
            aria-label="Close Copilot"
          >
            ×
          </button>
        )}
      </header>

      <form className="copilot-form" onSubmit={handleSubmit}>
        <label htmlFor="copilot-question">Your question</label>

        <textarea
          id="copilot-question"
          value={question}
          onChange={(event) => setQuestion(event.target.value)}
          placeholder="Which incidents need attention?"
          maxLength={1000}
          required
        />

        <button
          className="copilot-ask-button"
          type="submit"
          disabled={loading || !question.trim()}
        >
          {loading ? "Thinking…" : "Ask Copilot"}
        </button>
      </form>

      {loading && (
        <p className="copilot-status" role="status">
          Analysing the selected data. This may take a few seconds…
        </p>
      )}

      {error && (
        <div className="copilot-error" role="alert">
          {error}
        </div>
      )}

      {result && (
        <section className="copilot-result" aria-live="polite">
          {result.ungrounded && (
            <div className="copilot-warning">
              This response may not be sufficiently grounded in the available data.
              Verify it before taking action.
            </div>
          )}

          {result.refused && (
            <div className="copilot-warning">
              Copilot could not provide the requested answer.
            </div>
          )}

          {!result.answerable && (
            <div className="copilot-warning">
              The available data cannot answer this question reliably.
            </div>
          )}

          <h3>Answer</h3>
          <p className="copilot-answer">{result.answer}</p>

          {result.droppedCitations > 0 && (
            <p className="copilot-note">
              {result.droppedCitations} unsupported citation(s) were removed.
            </p>
          )}

          {result.sources.length > 0 && (
            <div className="copilot-sources">
              <h4>Sources</h4>

              <div className="copilot-source-list">
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

          {result.model && (
            <p className="copilot-meta">Model: {result.model}</p>
          )}
        </section>
      )}
    </aside>
  );
}