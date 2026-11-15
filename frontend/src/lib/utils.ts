import { type ClassValue, clsx } from "clsx"
import { extendTailwindMerge } from "tailwind-merge"
import { sfTheme } from "@/ds/theme"

// Склейка классов знает размеры, скругления, тени и высоты дизайн-системы (ds/theme.ts): без этого
// text-sf-note для неё — цвет текста, и cn('text-sf-note', 'text-sf-ink') молча выкидывает размер.
const twMerge = extendTailwindMerge({
  extend: {
    theme: {
      text: Object.keys(sfTheme.fontSize),
      radius: Object.keys(sfTheme.borderRadius),
      shadow: Object.keys(sfTheme.boxShadow),
      spacing: Object.keys(sfTheme.spacing),
    },
  },
})

export function cn(...inputs: ClassValue[]) {
  return twMerge(clsx(inputs))
}
