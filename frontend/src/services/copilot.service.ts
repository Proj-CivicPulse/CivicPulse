
export type CopilotSourceKind =
  | "incident"
  | "ward"
  | "category"
  | "hotspot"
  | "metric";

export interface CopilotQuery {
  question: string;
  wardId?: string;
  category?: string;
  from?: string;
  to?: string;
}

export interface CopilotScope {
  wardId?: string;
  category?: string;
  from?: string;
  to?: string;
}

export interface CopilotSource {
  id: string;
  kind: CopilotSourceKind;
  label: string;
  targetId?: string;
}

export interface CopilotResponse {
  answer: string;
  answerable: boolean;
  refused: boolean;
  ungrounded: boolean;
  droppedCitations: number;
  sources: CopilotSource[];
  scope?: {
    wardId?: string;
    wardName?: string;
    category?: string;
    categoryName?: string;
    from?: string;
    to?: string;
  };
  model?: string;
  generatedAt?: string;
}

export interface CopilotApiError {
  status: number;
  code: string;
  message: string;
  retryAfterSeconds?: number;
}

const ENDPOINT = "/api/core/copilot/query";

export async function askCopilot(
  query: CopilotQuery,
  signal?: AbortSignal,
): Promise<CopilotResponse> {
  const response = await fetch(ENDPOINT, {
    method: "POST",
    credentials: "include",
    headers: {
      "Content-Type": "application/json",
      Accept: "application/json",
    },
    body: JSON.stringify(query),
    signal,
  });

  const body: unknown = await response.json().catch(() => null);

  if (!response.ok) {
    const errorBody =
      body &&
      typeof body === "object" &&
      "error" in body &&
      body.error &&
      typeof body.error === "object"
        ? body.error
        : {};

    const details = errorBody as {
      code?: string;
      message?: string;
    };

    const error: CopilotApiError = {
      status: response.status,
      code: details.code ?? "REQUEST_FAILED",
      message:
        details.message ?? "The Copilot request failed.",
      retryAfterSeconds: parseRetryAfter(
        response.headers.get("Retry-After"),
      ),
    };

    throw error;
  }

  if (
    !body ||
    typeof body !== "object" ||
    !("answer" in body) ||
    typeof body.answer !== "string" ||
    !("answerable" in body) ||
    typeof body.answerable !== "boolean" ||
    !("refused" in body) ||
    typeof body.refused !== "boolean" ||
    !("ungrounded" in body) ||
    typeof body.ungrounded !== "boolean" ||
    !("sources" in body) ||
    !Array.isArray(body.sources)
  ) {
    const error: CopilotApiError = {
      status: 502,
      code: "INVALID_RESPONSE",
      message: "The Copilot returned an invalid response.",
    };

    throw error;
  }

  return body as CopilotResponse;
}

function parseRetryAfter(
  value: string | null,
): number | undefined {
  if (!value) {
    return undefined;
  }

  const seconds = Number(value);

  if (Number.isFinite(seconds) && seconds >= 0) {
    return seconds;
  }

  const retryDate = Date.parse(value);

  if (Number.isNaN(retryDate)) {
    return undefined;
  }

  return Math.max(
    0,
    Math.ceil((retryDate - Date.now()) / 1000),
  );
}

