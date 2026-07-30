import i18n from 'i18next';
import { initReactI18next } from 'react-i18next';
import uz from './locales/uz.json';
import ru from './locales/ru.json';
import en from './locales/en.json';

/**
 * Three languages, one source of truth: `uz.json` is the file features are written
 * against, and `ru.json` / `en.json` mirror its key set exactly. A missing key falls
 * back to Uzbek rather than rendering the raw key at the operator.
 */
export const LANGUAGES = [
  { code: 'uz', label: "O'zbekcha", short: 'UZ' },
  { code: 'ru', label: 'Русский', short: 'RU' },
  { code: 'en', label: 'English', short: 'EN' },
] as const;

export type LanguageCode = (typeof LANGUAGES)[number]['code'];

const STORAGE_KEY = 'qosid.lang';

const isSupported = (code: string | null | undefined): code is LanguageCode =>
  !!code && LANGUAGES.some((l) => l.code === code);

/**
 * The operator's saved choice, otherwise Uzbek. Browser-language detection is
 * deliberately NOT used: the console is deployed in Uzbek offices where a laptop set to
 * ru-RU or en-US is common, and having the console silently change language based on an
 * OS setting is a worse default than one predictable language plus a visible switch.
 */
function initialLanguage(): LanguageCode {
  try {
    const saved = localStorage.getItem(STORAGE_KEY);
    if (isSupported(saved)) return saved;
  } catch {
    // Private mode or a locked-down browser — fall through to the default.
  }
  return 'uz';
}

i18n.use(initReactI18next).init({
  resources: {
    uz: { translation: uz },
    ru: { translation: ru },
    en: { translation: en },
  },
  lng: initialLanguage(),
  fallbackLng: 'uz',
  interpolation: { escapeValue: false },
});

i18n.on('languageChanged', (code) => {
  try {
    localStorage.setItem(STORAGE_KEY, code);
  } catch {
    // Not being able to remember the choice must never break switching it.
  }
  if (typeof document !== 'undefined') document.documentElement.lang = code;
});

if (typeof document !== 'undefined') document.documentElement.lang = i18n.language;

export default i18n;
