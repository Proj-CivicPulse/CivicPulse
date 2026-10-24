import { useId, useState } from 'react';
import { Check, ImagePlus } from 'lucide-react';
import { complaintService, PHOTO_MAX_BYTES, PHOTO_TYPES, type Complaint } from '@/services/complaint.service';
import { describeError } from '@/lib/errors';
import Button from '@/components/ui/Button';
import styles from './PhotoAttach.module.css';

type State =
    | { step: 'idle' }
    | { step: 'uploading'; name: string }
    | { step: 'done' }
    | { step: 'error'; message: string; file: File | null };

/**
 * Adds one photo to a report that has already been filed
 * (docs/photo-upload-contract.md §6): the report is never held hostage to
 * the upload, a failure is retryable right here, and the bytes go straight to
 * storage — the server only issues permission and checks what arrived.
 */
export default function PhotoAttach({ complaint }: { complaint: Complaint }) {
    const inputId = useId();
    const [state, setState] = useState<State>({ step: 'idle' });
    const token = complaint.photoUploadToken ?? undefined;

    if (!token) {
        return <p className={styles.hint}>Photo upload isn&rsquo;t available right now.</p>;
    }

    async function upload(file: File) {
        if (!(PHOTO_TYPES as readonly string[]).includes(file.type)) {
            setState({ step: 'error', message: 'Choose a JPEG, PNG or WebP photo.', file: null });
            return;
        }
        if (file.size > PHOTO_MAX_BYTES) {
            setState({ step: 'error', message: 'That photo is over 8 MB. Choose a smaller one.', file: null });
            return;
        }
        setState({ step: 'uploading', name: file.name });
        try {
            // A fresh intent per attempt, so an expired one is never the problem.
            const intent = await complaintService.photoIntent(complaint.id, {
                contentType: file.type,
                sizeBytes: file.size,
                uploadToken: token,
            });
            const put = await fetch(intent.uploadUrl, {
                method: 'PUT',
                headers: { 'Content-Type': intent.contentType },
                body: file,
            });
            if (!put.ok) throw new Error(`upload ${put.status}`);
            await complaintService.confirmPhoto(complaint.id, { objectKey: intent.objectKey, uploadToken: token });
            setState({ step: 'done' });
        } catch (error) {
            const message =
                error instanceof Error && error.message.startsWith('upload ')
                    ? 'The upload did not go through. Check your connection and try again.'
                    : describeError(error, 'the photo').title;
            setState({ step: 'error', message, file });
        }
    }

    if (state.step === 'done') {
        return (
            <p className={styles.done}>
                <Check size={16} strokeWidth={2} aria-hidden="true" />
                Photo attached. The ward officer will see it with your report.
            </p>
        );
    }

    return (
        <div className={styles.box}>
            <p className={styles.hint}>
                Optional: one photo of the problem helps the officer judge it. The location and camera
                details stored in the file are removed.
            </p>
            <div className={styles.row}>
                <label htmlFor={inputId} className={styles.pick} aria-disabled={state.step === 'uploading'}>
                    <ImagePlus size={16} strokeWidth={2} aria-hidden="true" />
                    {state.step === 'uploading' ? `Uploading ${state.name}…` : 'Add a photo'}
                </label>
                <input
                    id={inputId}
                    className="srOnly"
                    type="file"
                    accept={PHOTO_TYPES.join(',')}
                    disabled={state.step === 'uploading'}
                    onChange={(e) => {
                        const file = e.target.files?.[0];
                        e.target.value = '';
                        if (file) void upload(file);
                    }}
                />
                {state.step === 'error' && state.file && (
                    <Button size="compact" variant="quiet" onClick={() => void upload(state.file as File)}>
                        Try again
                    </Button>
                )}
            </div>
            {state.step === 'error' && (
                <p className={styles.error} role="alert">
                    {state.message}
                </p>
            )}
        </div>
    );
}
