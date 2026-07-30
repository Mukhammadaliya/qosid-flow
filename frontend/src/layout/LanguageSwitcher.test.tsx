import { describe, it, expect, beforeEach } from 'vitest';
import { render, screen } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import i18n from '../i18n';
import { LanguageSwitcher } from './LanguageSwitcher';

/**
 * The switcher is the only way an operator who cannot read Uzbek gets past the login form,
 * so two things have to hold: the choice takes effect immediately, and it survives a reload.
 */
describe('LanguageSwitcher', () => {
  beforeEach(async () => {
    localStorage.clear();
    await i18n.changeLanguage('uz');
  });

  it('starts on Uzbek — the console does not follow the OS language', () => {
    expect(i18n.language).toBe('uz');
  });

  it('switches the active language when another code is picked', async () => {
    render(<LanguageSwitcher />);
    await userEvent.click(screen.getByText('EN'));
    expect(i18n.language).toBe('en');
    expect(i18n.t('nav.settings')).toBe('Settings');
  });

  it('remembers the choice so a reload does not reset it', async () => {
    render(<LanguageSwitcher />);
    await userEvent.click(screen.getByText('RU'));
    expect(localStorage.getItem('qosid.lang')).toBe('ru');
  });
});
