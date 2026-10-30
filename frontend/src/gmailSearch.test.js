import { describe, expect, it } from 'vitest';
import { validateGmailSearchFilters, validGmailSearchResponse } from './gmailSearch.js';

describe('Gmail search filters', () => {
  it('accepts up to three alphanumeric terms and an exact sender', () => {
    expect(validateGmailSearchFilters({ keyword: 'Interview salary 2026', sender: 'Recruiter@example.test' })).toEqual({
      filters: { keyword: 'Interview salary 2026', sender: 'Recruiter@example.test' },
    });
  });

  it.each(['interview*', 'two-word', 'one two three four', 'a'.repeat(41)])('rejects unsafe or excessive keyword terms: %s', keyword => {
    expect(validateGmailSearchFilters({ keyword }).error).toMatch(/alphanumeric/);
  });

  it('rejects malformed sender and requires both dates together', () => {
    expect(validateGmailSearchFilters({ sender: 'not-an-address' }).error).toMatch(/exact sender/);
    expect(validateGmailSearchFilters({ keyword: 'interview', startDate: '2026-10-01' }).error).toMatch(/both dates/);
  });

  it('accepts an inclusive range up to 366 calendar days', () => {
    expect(validateGmailSearchFilters({ startDate: '2024-01-01', endDate: '2024-12-31' })).toEqual({
      filters: { start_date: '2024-01-01', end_date: '2024-12-31' },
    });
    expect(validateGmailSearchFilters({ startDate: '2024-01-01', endDate: '2025-01-01' }).error).toMatch(/366 inclusive days/);
  });

  it('rejects invalid, reversed, unsupported maximum dates and empty plans', () => {
    expect(validateGmailSearchFilters({ startDate: '2026-02-30', endDate: '2026-03-01' }).error).toMatch(/valid calendar dates/);
    expect(validateGmailSearchFilters({ startDate: '2026-10-02', endDate: '2026-10-01' }).error).toMatch(/To date/);
    expect(validateGmailSearchFilters({ startDate: '9999-12-01', endDate: '9999-12-31' }).error).toMatch(/valid calendar dates/);
    expect(validateGmailSearchFilters({}).error).toMatch(/Enter a keyword/);
  });
});

describe('Gmail search response validation', () => {
  const row = { subject: 'Synthetic subject', sender: 'sender@example.test', received_at: '2026-10-19T12:30:00Z',
    excerpt: 'Synthetic excerpt', selection_id: 's'.repeat(43) };
  it('accepts only the bounded transient response shape', () => {
    expect(validGmailSearchResponse({ results: [row], partial_results: false, coverage_warning: null })).toBe(true);
  });
  it('requires a coverage warning for partial results and rejects exposed identifiers', () => {
    expect(validGmailSearchResponse({ results: [row], partial_results: true, coverage_warning: 'More results may exist.' })).toBe(true);
    expect(validGmailSearchResponse({ results: [row], partial_results: true, coverage_warning: null })).toBe(false);
    expect(validGmailSearchResponse({ results: [{ ...row, message_id: 'raw-id' }], partial_results: false, coverage_warning: null })).toBe(false);
    expect(validGmailSearchResponse({ results: [row, row], partial_results: false, coverage_warning: null })).toBe(false);
    expect(validGmailSearchResponse({ results: [row], partial_results: true, coverage_warning: '  ' })).toBe(false);
    expect(validGmailSearchResponse({ results: [{ ...row, received_at: 'October 19, 2026' }], partial_results: false, coverage_warning: null })).toBe(false);
    expect(validGmailSearchResponse({ results: Array(21).fill(row), partial_results: false, coverage_warning: null })).toBe(false);
  });
});
