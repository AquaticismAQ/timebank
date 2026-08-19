import { useState, useEffect, useMemo } from 'react';
import { LayoutDashboard, ClipboardCheck, FolderOpen, Users, Wallet, BarChart2, Settings, User, FileQuestion, Lightbulb } from 'lucide-react';
import { useTranslation } from 'react-i18next';
import { useAuth } from '../../App';
import { api } from '../../api/client';
import { AppShell, type ShellNavItem } from './AppShell';

export function AdminLayout() {
  const { t } = useTranslation();
  const { user } = useAuth();
  const [unreadCount, setUnreadCount] = useState(0);
  const [pendingReviews, setPendingReviews] = useState(0);

  useEffect(() => {
    api.notifications.unreadCount().then(setUnreadCount).catch(() => {});
    api.reviewQueue.summary().then(s => setPendingReviews(s.pending)).catch(() => {});
  }, []);

  const navItems = useMemo((): ShellNavItem[] => [
    { to: '/admin/dashboard', icon: LayoutDashboard, label: t('layout.admin.dashboard') },
    { to: '/admin/audit', icon: ClipboardCheck, label: t('layout.admin.auditCenter'), badge: pendingReviews || undefined },
    { to: '/admin/cases', icon: FileQuestion, label: t('layout.admin.caseReview') },
    { to: '/admin/proposals', icon: Lightbulb, label: t('layout.admin.proposalReview') },
    { to: '/admin/projects', icon: FolderOpen, label: t('layout.admin.projectManagement') },
    { to: '/admin/students', icon: Users, label: t('layout.admin.studentManagement') },
    { to: '/admin/accounts', icon: Wallet, label: t('layout.admin.timeCoinAccounts') },
    { to: '/admin/reports', icon: BarChart2, label: t('layout.admin.reports') },
    { to: '/admin/system', icon: Settings, label: t('layout.admin.systemAdmin') },
    { to: '/admin/profile', icon: User, label: t('layout.admin.profile') },
  ], [t, pendingReviews]);

  return (
    <AppShell
      navItems={navItems}
      brandSubtitle={t('layout.admin.brandSubtitle')}
      unreadCount={unreadCount}
    />
  );
}
