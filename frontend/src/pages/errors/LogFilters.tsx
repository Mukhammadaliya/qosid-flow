import type { ReactNode } from 'react';
import { Space, Input, Select, DatePicker, Button } from 'antd';
import { useTranslation } from 'react-i18next';
import type { Dayjs } from 'dayjs';

export interface FilterState {
  q: string;
  level: string;
  range: [Dayjs, Dayjs] | null;
}

export function LogFilters({
  value, onChange, onRefresh, showLevel = true, extra,
}: {
  value: FilterState;
  onChange: (v: FilterState) => void;
  onRefresh: () => void;
  showLevel?: boolean;
  extra?: ReactNode;
}) {
  const { t } = useTranslation();
  return (
    <Space wrap style={{ marginBottom: 12 }}>
      {extra}
      {showLevel && (
        <Select
          style={{ width: 120 }}
          value={value.level}
          onChange={(level) => onChange({ ...value, level })}
          options={[
            { value: 'ERROR', label: 'ERROR' },
            { value: 'WARN', label: 'WARN' },
            { value: '', label: t('errors.allLevels') },
          ]}
        />
      )}
      <DatePicker.RangePicker
        showTime
        value={value.range as any}
        onChange={(range) => onChange({ ...value, range: range as any })}
      />
      <Input.Search
        allowClear
        placeholder={t('errors.searchText')}
        style={{ width: 200 }}
        onSearch={(q) => onChange({ ...value, q })}
      />
      <Button onClick={onRefresh}>{t('errors.refresh')}</Button>
    </Space>
  );
}
