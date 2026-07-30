import { Segmented } from 'antd';
import { useTranslation } from 'react-i18next';
import { LANGUAGES, type LanguageCode } from '../i18n';

/**
 * Three languages, so a segmented control rather than a dropdown: the choice is always
 * visible and switching costs one click. The codes (UZ/RU/EN) carry the full label as a
 * tooltip, which keeps the header narrow without hiding what the options are.
 */
export function LanguageSwitcher({ size = 'small' }: { size?: 'small' | 'middle' }) {
  const { i18n, t } = useTranslation();
  const current = (LANGUAGES.find((l) => l.code === i18n.language)?.code ?? 'uz') as LanguageCode;

  return (
    <Segmented
      size={size}
      aria-label={t('nav.language')}
      value={current}
      onChange={(value) => i18n.changeLanguage(value as LanguageCode)}
      options={LANGUAGES.map((l) => ({ value: l.code, label: l.short, title: l.label }))}
    />
  );
}
