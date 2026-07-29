import { api } from './client';

const BASE = '/qosid/admin/api/stats';

// Backend serializes the record StatPoint(double t, double v) as {"t":..,"v":..}
export interface StatPoint { t: number; v: number } // t = epoch SECONDS, v = value
export interface StatSeries { name: string; points: StatPoint[] }
export interface StatsResult { series: StatSeries[]; kpis: Record<string, number | null>; error?: string }

export interface StatsParams { from?: number; to?: number; step?: number }

export const queryStats = (preset: string, params: StatsParams = {}) =>
  api.get<StatsResult>(BASE, { params: { preset, ...params } }).then(r => r.data);
