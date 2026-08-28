import { Component, type ReactNode } from 'react';

interface Props {
    children: ReactNode;
    fallback: (error: Error, retry: () => void) => ReactNode;
}

interface State {
    error: Error | null;
}

// React error boundaries still require a class component as of React 19 —
// there's no hook-based equivalent.
export class ErrorBoundary extends Component<Props, State> {
    state: State = { error: null };

    static getDerivedStateFromError(error: Error): State {
        return { error };
    }

    componentDidCatch(error: Error, info: { componentStack: string }) {
        console.error('Uncaught render error:', error, info.componentStack);
    }

    retry = () => this.setState({ error: null });

    render() {
        if (this.state.error) {
            return this.props.fallback(this.state.error, this.retry);
        }
        return this.props.children;
    }
}