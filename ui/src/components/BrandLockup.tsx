import type { CSSProperties, HTMLAttributes } from "react";
import { BRAND_NAME, BRAND_WORDMARK_ASPECT, BRAND_WORDMARK_SRC } from "@/lib/brand";

interface BrandLockupProps extends Omit<HTMLAttributes<HTMLSpanElement>, "children"> {
  decorative?: boolean;
  title?: string;
}

/**
 * The brand wordmark. The artwork is used as a mask filled with
 * `currentColor`, so the same file reads correctly in light and dark themes.
 *
 * Size it with a height class (`h-5`); the width follows the artwork's aspect.
 */
export function BrandLockup({ decorative = false, title = BRAND_NAME, className, style, ...rest }: BrandLockupProps) {
  const mask = `url("${BRAND_WORDMARK_SRC}") center / contain no-repeat`;
  const maskStyle: CSSProperties = {
    aspectRatio: String(BRAND_WORDMARK_ASPECT),
    backgroundColor: "currentColor",
    WebkitMask: mask,
    mask,
    ...style,
  };
  return (
    <span
      {...rest}
      className={["inline-block", className].filter(Boolean).join(" ")}
      style={maskStyle}
      role={decorative ? undefined : "img"}
      aria-hidden={decorative ? true : undefined}
      aria-label={decorative ? undefined : title}
    />
  );
}
