import { ReactNode } from 'react';
import { Navigate } from 'react-router-dom';
import { Spin } from 'antd';
import { useAuth } from './AuthContext';

export function RequireAuth({ children }: { children: ReactNode }) {
  const { user, loading } = useAuth();
  if (loading) return <Spin style={{ display: 'block', marginTop: '20vh' }} />;
  if (!user) return <Navigate to="/login" replace />;
  return <>{children}</>;
}
