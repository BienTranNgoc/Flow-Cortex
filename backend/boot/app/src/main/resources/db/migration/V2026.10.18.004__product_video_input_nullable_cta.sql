-- Make cta optional on product_video_input so AI can generate the call to action automatically.

ALTER TABLE product_video_input ALTER COLUMN cta DROP NOT NULL;
