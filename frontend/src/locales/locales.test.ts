import { describe, it, expect } from 'vitest';
import uz from './uz.json';
import ru from './ru.json';
import en from './en.json';

/**
 * `uz.json` is the file features are written against; `ru.json` and `en.json` mirror it.
 * The failure this guards is quiet: someone adds a key for a new screen, ships it, and
 * Russian and English operators see the raw key string in the UI. A missing translation
 * should break the build, not the console.
 */

const catalogues = { ru, en } as const;

const placeholders = (value: string) =>
  (value.match(/\{\{[^}]+\}\}|\{\d+\}|%[sd]/g) ?? []).sort();

describe('locale catalogues', () => {
  it.each(Object.keys(catalogues))('%s has exactly the keys uz has, in the same order', (lang) => {
    const target = catalogues[lang as keyof typeof catalogues] as Record<string, string>;
    expect(Object.keys(target)).toEqual(Object.keys(uz));
  });

  it.each(Object.keys(catalogues))('%s has no blank values', (lang) => {
    const target = catalogues[lang as keyof typeof catalogues] as Record<string, string>;
    const blank = Object.entries(target).filter(([, v]) => !String(v).trim()).map(([k]) => k);
    expect(blank).toEqual([]);
  });

  it.each(Object.keys(catalogues))('%s keeps every interpolation placeholder', (lang) => {
    const target = catalogues[lang as keyof typeof catalogues] as Record<string, string>;
    const source = uz as Record<string, string>;
    const mismatched = Object.keys(source).filter(
      (k) => placeholders(source[k]).join() !== placeholders(target[k]).join(),
    );
    expect(mismatched).toEqual([]);
  });

  /**
   * Some values are meant to stay identical across languages — product names, config keys,
   * protocol labels. This pins the count so a genuinely untranslated string cannot hide
   * among them: if it moves, someone must look and either translate or extend the note.
   */
  it('leaves only the known non-translatable values untouched', () => {
    const source = uz as Record<string, string>;
    const untouched = (target: Record<string, string>) =>
      Object.keys(source).filter((k) => source[k] === target[k]);

    expect(untouched(ru as Record<string, string>).length).toBeLessThanOrEqual(14);
    expect(untouched(en as Record<string, string>).length).toBeLessThanOrEqual(20);
  });
});
