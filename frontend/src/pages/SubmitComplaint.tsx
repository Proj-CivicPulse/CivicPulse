import { useState } from 'react';
import { useNavigate } from 'react-router-dom';
import { complaintService } from '../services/complaint.service';
import { ApiError } from '../services/api';

export default function SubmitComplaint() {
    const navigate = useNavigate();
    const [category, setCategory] = useState('');
    const [wardId, setWardId] = useState('');
    const [description, setDescription] = useState('');
    const [lat, setLat] = useState<number | null>(null);
    const [long, setLong] = useState<number | null>(null);
    const [error, setError] = useState<string | null>(null);
    const [submitting, setSubmitting] = useState(false);

    function useMyLocation() {
        if (!navigator.geolocation) {
            setError('Location is not supported by this browser.');
            return;
        }
        navigator.geolocation.getCurrentPosition(
            (position) => {
                setLat(position.coords.latitude);
                setLong(position.coords.longitude);
            },
            () => setError('Could not get your location. Please try again.')
        );
    }

    async function handleSubmit(e: React.SubmitEvent<HTMLFormElement>) {
        e.preventDefault();
        setError(null);

        if (lat === null || long === null) {
            setError('Location is required — tap "Use my location" above.');
            return;
        }

        setSubmitting(true);
        try {
            await complaintService.create({ category, wardId, description, lat, long });
            navigate('/my-complaints');
        } catch (err) {
            setError(err instanceof ApiError ? err.message : 'Could not submit complaint. Please try again.');
        } finally {
            setSubmitting(false);
        }
    }

    return (
        <div>
            <h1>Submit a Complaint</h1>
            <form onSubmit={handleSubmit}>
                {error && <p role="alert">{error}</p>}

                <label htmlFor="category">Category</label>
                <input id="category" required value={category} onChange={(e) => setCategory(e.target.value)} />

                {/* TODO: replace with a real ward picker once GET /wards exists */}
                <label htmlFor="wardId">Ward</label>
                <input id="wardId" required value={wardId} onChange={(e) => setWardId(e.target.value)} />

                <label htmlFor="description">Description</label>
                <textarea id="description" required value={description} onChange={(e) => setDescription(e.target.value)} />

                <button type="button" onClick={useMyLocation}>
                    Use my location
                </button>
                {lat !== null && long !== null && (
                    <p>
                        Location set: {lat.toFixed(5)}, {long.toFixed(5)}
                    </p>
                )}

                {/* TODO: photo upload — depends on the open decision in docs/endpoints.md */}

                <button type="submit" disabled={submitting}>
                    {submitting ? 'Submitting…' : 'Submit Complaint'}
                </button>
            </form>
        </div>
    );
}