-- Images are uploaded to the server and kept as thumbnails on disk (image_path);
-- no URL pointing outside the instance is stored.
ALTER TABLE recipe DROP COLUMN image_url;
