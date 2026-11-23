/**
 * The product brand shown to people using this instance.
 *
 * Truelay runs this fork of Paperclip as "Truelay EggBreak". Only the
 * user-facing name, logo and icons change here. Package names, the
 * `paperclipai` CLI, `PAPERCLIP_*` settings and API paths keep the upstream
 * names so upstream updates and company imports keep working.
 *
 * The icon files live in `ui/public/` (favicons, app icons) and
 * `ui/public/brand/`. The HTML title and web app manifest repeat
 * PRODUCT_NAME because they are static files.
 */
export const BRAND_NAME = "Truelay";
export const PRODUCT_NAME = "Truelay EggBreak";

/** Transparent wordmark; only its alpha channel is used, so it follows the text color. */
export const BRAND_WORDMARK_SRC = "/brand/truelay-wordmark.png";
/** Width divided by height of the wordmark artwork. */
export const BRAND_WORDMARK_ASPECT = 586 / 160;
