import { describe, it, expect, vi, beforeEach } from 'vitest';
import { render, screen, waitFor } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import '../../i18n';
import { AiConfigPage } from './AiConfigPage';
import * as aiApi from '../../api/ai';

beforeEach(() => vi.restoreAllMocks());

describe('AiConfigPage', () => {
  it('loads config and saves without resending the masked key', async () => {
    vi.spyOn(aiApi, 'getAiConfig').mockResolvedValue({ baseUrl: 'https://llm/v1', apiKey: '***', model: 'm1' });
    const put = vi.spyOn(aiApi, 'putAiConfig').mockResolvedValue({} as any);
    render(<AiConfigPage />);
    await waitFor(() => expect(screen.getByDisplayValue('https://llm/v1')).toBeInTheDocument());
    await userEvent.click(screen.getByRole('button', { name: 'Saqlash' }));
    await waitFor(() => expect(put).toHaveBeenCalled());
    // masked key must NOT be resent as the literal ***
    expect(put.mock.calls[0][0].apiKey === '***').toBe(false);
  });
});
