import { Link } from 'react-router-dom';
import type { AnchorHTMLAttributes, ButtonHTMLAttributes, ReactNode } from 'react';
import styles from './Button.module.css';

export type ButtonVariant = 'primary' | 'secondary' | 'quiet';
export type ButtonSize = 'default' | 'compact';

interface CommonProps {
    variant?: ButtonVariant;
    /** 36px default, 28px compact. Both grow to 44px on coarse pointers. */
    size?: ButtonSize;
    fullWidth?: boolean;
    className?: string;
}

/**
 * A button with no visible text must carry an aria-label. Expressing that as a
 * union rather than a runtime check means TypeScript rejects the icon-only
 * button that forgot one, at the call site, before it can ship.
 */
type LabelProps =
    | { children: ReactNode; 'aria-label'?: string }
    | { children?: never; 'aria-label': string };

type AsButton = CommonProps &
    LabelProps &
    Omit<ButtonHTMLAttributes<HTMLButtonElement>, 'children' | 'aria-label' | 'className'> & {
        to?: never;
    };

type AsLink = CommonProps &
    LabelProps &
    Omit<AnchorHTMLAttributes<HTMLAnchorElement>, 'children' | 'aria-label' | 'className' | 'href'> & {
        /** Renders a router Link instead of a button, styled identically. */
        to: string;
    };

export type ButtonProps = AsButton | AsLink;

export default function Button(props: ButtonProps) {
    const {
        variant = 'primary',
        size = 'default',
        fullWidth = false,
        className,
        children,
        ...rest
    } = props;

    const classes = [
        styles.btn,
        styles[variant],
        styles[size],
        fullWidth ? styles.fullWidth : '',
        className ?? '',
    ]
        .filter(Boolean)
        .join(' ');

    if ('to' in rest && rest.to !== undefined) {
        const { to, ...anchorProps } = rest;
        return (
            <Link to={to} className={classes} {...anchorProps}>
                {children}
            </Link>
        );
    }

    const { to: _ignored, ...buttonProps } = rest as { to?: never } & ButtonHTMLAttributes<HTMLButtonElement>;
    return (
        <button type="button" className={classes} {...buttonProps}>
            {children}
        </button>
    );
}
