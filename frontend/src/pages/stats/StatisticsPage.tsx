import { useCallback, useEffect, useState } from 'react';
import { Card, Row, Col, Statistic, Alert, Space, Select, Switch } from 'antd';
import { Line, Column, Funnel } from '@ant-design/plots';
import { useTranslation } from 'react-i18next';
import { queryStats, StatsResult, StatSeries } from '../../api/stats';
import { BRAND_COLORS } from './statChartTheme';

const RANGES: Record<string, number> = { '15m': 900_000, '1h': 3_600_000, '6h': 21_600_000, '24h': 86_400_000 };

// x-axis keeps the raw epoch millis (avoids key collisions across 24h); labels formatted on the axis.
function toChartData(series: StatSeries[]) {
  return series.flatMap((s) =>
    s.points.map((p) => ({ time: p.t * 1000, series: s.name, value: p.v })));
}

const TIME_AXIS = { x: { labelFormatter: (ms: number) => new Date(ms).toLocaleTimeString() } };

const FUNNEL_STAGES = ['pull', 'produce', 'consume', 'http', 'save'];

export function StatisticsPage() {
  const { t } = useTranslation();
  const [rangeKey, setRangeKey] = useState('1h');
  const [auto, setAuto] = useState(true);
  const [kpi, setKpi] = useState<StatsResult>({ series: [], kpis: {} });
  const [throughput, setThroughput] = useState<StatsResult>({ series: [], kpis: {} });
  const [funnel, setFunnel] = useState<StatsResult>({ series: [], kpis: {} });
  const [errors, setErrors] = useState<StatsResult>({ series: [], kpis: {} });
  const [latency, setLatency] = useState<StatsResult>({ series: [], kpis: {} });
  const [error, setError] = useState<string | undefined>();

  const load = useCallback(() => {
    const to = Date.now();
    const from = to - RANGES[rangeKey];
    const params = { from, to, step: 30 };
    Promise.all([
      queryStats('kpi', params), queryStats('throughput', params),
      queryStats('funnel', params), queryStats('errors', params), queryStats('latency', params),
    ])
      .then(([k, tp, fn, er, lt]) => {
        setKpi(k); setThroughput(tp); setFunnel(fn); setErrors(er); setLatency(lt);
        setError(k.error ?? tp.error ?? fn.error ?? er.error ?? lt.error);
      })
      .catch(() => setError(t('stats.loadFailed')));
  }, [rangeKey, t]);

  useEffect(() => {
    load();
    if (!auto) return;
    const id = setInterval(load, 30_000);
    return () => clearInterval(id);
  }, [load, auto]);

  const funnelData = FUNNEL_STAGES.map((stage) => ({ stage, count: funnel.kpis[stage] ?? 0 }));

  const n = (key: string, digits = 0) => {
    const v = kpi.kpis[key];
    return v == null ? '-' : Number(v).toFixed(digits);
  };

  return (
    <div>
      <Space style={{ marginBottom: 16 }}>
        <Select value={rangeKey} onChange={setRangeKey} style={{ width: 100 }}
          options={Object.keys(RANGES).map((k) => ({ value: k, label: k }))} />
        <Switch checked={auto} onChange={setAuto} checkedChildren={t('stats.autoOn')} unCheckedChildren={t('stats.autoOff')} />
      </Space>

      {error && <Alert type="error" style={{ marginBottom: 16 }} message={error} />}

      <Row gutter={16} style={{ marginBottom: 16 }}>
        <Col span={5}><Card><Statistic title={t('stats.throughput')} value={n('throughput', 2)} suffix="req/s" /></Card></Col>
        <Col span={5}><Card><Statistic title={t('stats.successRate')} value={n('successRate', 1)} suffix="%" /></Card></Col>
        <Col span={4}><Card><Statistic title={t('stats.errorRate')} value={n('errorRate', 1)} suffix="%" /></Card></Col>
        <Col span={5}><Card><Statistic title={t('stats.dlq')} value={n('dlq')} /></Card></Col>
        <Col span={5}><Card><Statistic title={t('stats.e2eP95')} value={n('e2eP95')} suffix="ms" /></Card></Col>
      </Row>

      <Row gutter={16}>
        <Col span={12}>
          <Card title={t('stats.throughputChart')} style={{ marginBottom: 16 }}>
            <Line data={toChartData(throughput.series)} xField="time" yField="value" seriesField="series"
              colorField="series" scale={{ color: { range: BRAND_COLORS } }} axis={TIME_AXIS} height={240} />
          </Card>
        </Col>
        <Col span={12}>
          <Card title={t('stats.funnelChart')} style={{ marginBottom: 16 }}>
            <Funnel data={funnelData} xField="stage" yField="count" height={240}
              scale={{ color: { range: BRAND_COLORS } }} />
          </Card>
        </Col>
        <Col span={12}>
          <Card title={t('stats.errorsChart')}>
            <Column data={toChartData(errors.series)} xField="time" yField="value" stack colorField="series"
              scale={{ color: { range: BRAND_COLORS } }} axis={TIME_AXIS} height={240} />
          </Card>
        </Col>
        <Col span={12}>
          <Card title={t('stats.latencyChart')}>
            <Line data={toChartData(latency.series)} xField="time" yField="value" seriesField="series"
              colorField="series" scale={{ color: { range: BRAND_COLORS } }} axis={TIME_AXIS} height={240} />
          </Card>
        </Col>
      </Row>
    </div>
  );
}
