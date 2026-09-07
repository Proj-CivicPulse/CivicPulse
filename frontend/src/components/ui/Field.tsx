import { useId, useState } from 'react';
import { Eye, EyeOff } from 'lucide-react';
import type {
    InputHTMLAttributes,
    ReactNode,
    SelectHTMLAttributes,
    TextareaHTMLAttributes,
} from 'react';
import styles from './Field.module.css';

interface FieldBase {
    /** Rendered as a real <label htmlFor>, never a placeholder standing in for one. */
    label: string;
    /** Guidance shown below the control. Replaced by `error` when one is present. */
    helper?: string;
    error?: string;
    /** Shown next to the label, e.g. a character count. */
    aside?: ReactNode;
    className?: string;
}

type AsInput = FieldBase & { as?: 'input' } & Omit<
        InputHTMLAttributes<HTMLInputElement>,
        'id' | 'className' | 'aria-describedby' | 'aria-invalid'
    >;

type AsTextarea = FieldBase & { as: 'textarea' } & Omit<
        TextareaHTMLAttributes<HTMLTextAreaElement>,
        'id' | 'className' | 'aria-describedby' | 'aria-invalid'
    >;

type AsSelect = FieldBase & { as: 'select'; children: ReactNode } & Omit<
        SelectHTMLAttributes<HTMLSelectElement>,
        'id' | 'className' | 'aria-describedby' | 'aria-invalid'
    >;

export type FieldProps = AsInput | AsTextarea | AsSelect;

/**
 * Label above, control, helper below. The error replaces the helper rather than
 * stacking under it, so the block never changes height and the surrounding
 * layout doesn't jump when validation fires.
 *
 * A `type="password"` input automatically gains a reveal toggle. It lives here
 * rather than in the pages so every password input in the app gets the same
 * control with the same accessible wiring, instead of each form rolling its own.
 */
export default function Field(props: FieldProps) {
    const generatedId = useId();
    const [revealed, setRevealed] = useState(false);

    const { label, helper, error, aside, className, ...rest } = props;

    const id = `field-${generatedId}`;
    const describedBy =
        error !== undefined ? `${id}-error` : helper !== undefined ? `${id}-helper` : undefined;

    const controlClass = [styles.control, error !== undefined ? styles.invalid : '']
        .filter(Boolean)
        .join(' ');

    const shared = {
        id,
        'aria-describedby': describedBy,
        'aria-invalid': error !== undefined ? true : undefined,
    } as const;

    let control: ReactNode;

    if (rest.as === 'textarea') {
        const { as: _as, ...textareaProps } = rest;
        control = <textarea {...shared} className={controlClass} {...textareaProps} />;
    } else if (rest.as === 'select') {
        const { as: _as, children, ...selectProps } = rest;
        control = (
            <select {...shared} className={controlClass} {...selectProps}>
                {children}
            </select>
        );
    } else {
        const { as: _as, type, ...inputProps } = rest;

        if (type === 'password') {
            control = (
                <div className={styles.revealWrap}>
                    <input
                        {...shared}
                        // Swapping the type is what actually reveals the value.
                        // autoComplete is left untouched so password managers
                        // still recognise the field in either state.
                        type={revealed ? 'text' : 'password'}
                        className={`${controlClass} ${styles.hasReveal}`}
                        {...inputProps}
                    />
                    <button
                        type="button"
                        className={styles.revealButton}
                        // aria-pressed carries the state, and the label says what
                        // pressing it will DO — so a screen reader user is not
                        // told "hide password" on a field that is already hidden.
                        aria-pressed={revealed}
                        aria-label={revealed ? 'Hide password' : 'Show password'}
                        aria-controls={id}
                        onClick={() => setRevealed((current) => !current)}
                    >
                        {revealed ? (
                            <EyeOff size={18} strokeWidth={1.75} aria-hidden="true" />
                        ) : (
                            <Eye size={18} strokeWidth={1.75} aria-hidden="true" />
                        )}
                    </button>
                </div>
            );
        } else {
            control = <input {...shared} type={type} className={controlClass} {...inputProps} />;
        }
    }

    return (
        <div className={[styles.field, className ?? ''].filter(Boolean).join(' ')}>
            <div className={styles.labelRow}>
                <label className={styles.label} htmlFor={id}>
                    {label}
                </label>
                {aside !== undefined && <span className={styles.aside}>{aside}</span>}
            </div>

            {control}

            {error !== undefined ? (
                <p className={styles.error} id={`${id}-error`}>
                    {error}
                </p>
            ) : helper !== undefined ? (
                <p className={styles.helper} id={`${id}-helper`}>
                    {helper}
                </p>
            ) : null}
        </div>
    );
}
