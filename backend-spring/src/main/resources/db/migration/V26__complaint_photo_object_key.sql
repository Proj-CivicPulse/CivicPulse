-- Photo upload ships (docs/photo-upload-contract.md). The column the contract
-- reserved held a URL in name only: it was never written, and §4 decided it
-- stores an OBJECT KEY — a stored URL would bake in a hostname and a signing
-- scheme that both change. Renamed to say so.
--
-- The key is the re-encoded, EXIF-stripped JPEG the server wrote itself; its
-- thumbnail sits beside it at the same key with "-thumb" before ".jpg".
-- Nothing here is ever a URL: an officer's browser gets a short-lived signed
-- GET minted per request.

ALTER TABLE complaints RENAME COLUMN photo_url TO photo_object_key;
ALTER TABLE complaints ALTER COLUMN photo_object_key TYPE VARCHAR(255);
ALTER TABLE complaints ADD COLUMN photo_attached_at TIMESTAMPTZ(6);

-- Every existing value is NULL (contract §8), so nothing needs converting.
ALTER TABLE complaints ADD CONSTRAINT ck_complaints_photo_key
    CHECK (photo_object_key IS NULL OR photo_object_key LIKE 'photos/%');

COMMENT ON COLUMN complaints.photo_object_key IS
    'Object key of the server-written photo (photos/{complaintId}/{uuid}.jpg). '
    'Never a URL, never caller-supplied.';
