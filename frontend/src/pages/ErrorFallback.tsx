interface Props {
    error: Error;
    retry: () => void;
}

export default function ErrorFallback({ error, retry }: Props) {
    return (
        <div role="alert">
            <h1>Something went wrong</h1>
            <p>{error.message}</p>
            <button onClick={retry}>Try again</button>
        </div>
    );
}