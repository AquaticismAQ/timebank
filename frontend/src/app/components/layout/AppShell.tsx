import { Outlet, NavLink, useNavigate } from 'react-router';
import { Bell, Sun, Moon, Monitor, LogOut, Menu, X, Clock, ChevronRight } from 'lucide-react';
import { useState } from 'react';
import { useTranslation } from 'react-i18next';
import { useAuth, useTheme } from '../../App';
import { LanguageSwitcher } from '../LanguageSwitcher';

export interface ShellNavItem {
  to: string;
  icon: React.ComponentType<{ className?: string }>;
  label: string;
  badge?: number;
}

// Module-level component — stable identity across AppShell re-renders
interface SidebarContentProps {
  navItems: ShellNavItem[];
  brandSubtitle: string;
  statsStrip?: React.ReactNode;
  onNavClick?: () => void;
}

function SidebarContent({ navItems, brandSubtitle, statsStrip, onNavClick }: SidebarContentProps) {
  const { t } = useTranslation();
  return (
    <div className="flex flex-col h-full">
      <div className="px-4 py-5 border-b border-sidebar-border flex items-center gap-3 shrink-0">
        <div className="w-9 h-9 bg-sidebar-primary rounded-xl flex items-center justify-center shrink-0">
          <Clock className="w-5 h-5 text-sidebar-primary-foreground" />
        </div>
        <div>
          <div className="text-sidebar-foreground font-semibold text-sm">{t('common.appName')}</div>
          <div className="text-sidebar-foreground/60 text-xs">{brandSubtitle}</div>
        </div>
      </div>

      {statsStrip && (
        <div className="px-4 py-3 border-b border-sidebar-border shrink-0">
          {statsStrip}
        </div>
      )}

      <nav className="flex-1 px-3 py-4 space-y-0.5 overflow-auto">
        {navItems.map(({ to, icon: Icon, label, badge }) => (
          <NavLink
            key={to}
            to={to}
            onClick={onNavClick}
            className={({ isActive }) =>
              `flex items-center gap-3 px-3 py-2.5 rounded-xl text-sm transition-colors ${
                isActive
                  ? 'bg-sidebar-accent text-sidebar-accent-foreground font-medium'
                  : 'text-sidebar-foreground/70 hover:bg-sidebar-accent/60 hover:text-sidebar-foreground'
              }`
            }
          >
            {({ isActive: _isActive }) => (
              <>
                <Icon className="w-4 h-4 shrink-0" />
                <span className="flex-1">{label}</span>
                {badge != null && badge > 0 && (
                  <span className="bg-destructive text-destructive-foreground text-xs px-1.5 py-0.5 rounded-full leading-none">
                    {badge}
                  </span>
                )}
              </>
            )}
          </NavLink>
        ))}
      </nav>
    </div>
  );
}

export interface AppShellProps {
  navItems: ShellNavItem[];
  /** Enables mobile bottom nav (student mode) */
  bottomNavItems?: ShellNavItem[];
  /** Sidebar subtitle e.g. "学生入口" | "管理后台" */
  brandSubtitle: string;
  /** Left text in desktop header bar */
  headerInfo?: string;
  /** Optional stats strip shown below brand in sidebar */
  statsStrip?: React.ReactNode;
  /** If set, the bell icon navigates to this path */
  notificationsPath?: string;
  unreadCount?: number;
  /** If set and the user has that workspace, show a workspace-switch button */
  switchTarget?: { workspace: 'student' | 'admin'; path: string; label: string };
}

export function AppShell({
  navItems,
  bottomNavItems,
  brandSubtitle,
  headerInfo,
  statsStrip,
  notificationsPath,
  unreadCount = 0,
  switchTarget,
}: AppShellProps) {
  const { user, logout, setWorkspace } = useAuth();
  const { theme, setTheme } = useTheme();
  const { t } = useTranslation();
  const navigate = useNavigate();
  const [sidebarOpen, setSidebarOpen] = useState(false);

  const themeOptions: { value: 'light' | 'dark' | 'auto'; icon: React.ReactNode; label: string }[] = [
    { value: 'light', icon: <Sun className="w-4 h-4" />, label: t('common.theme.light') },
    { value: 'dark', icon: <Moon className="w-4 h-4" />, label: t('common.theme.dark') },
    { value: 'auto', icon: <Monitor className="w-4 h-4" />, label: t('common.theme.auto') },
  ];

  const canSwitch = switchTarget != null && user?.workspaces.includes(switchTarget.workspace);

  const handleSwitch = () => {
    if (!switchTarget) return;
    setWorkspace(switchTarget.workspace);
    navigate(switchTarget.path);
    setSidebarOpen(false);
  };

  const handleLogout = () => { logout(); navigate('/login'); };

  const sidebarProps: SidebarContentProps = {
    navItems,
    brandSubtitle,
    statsStrip,
  };

  return (
    <div className="min-h-screen bg-background flex">

      {/* ── Desktop sidebar ──────────────────────────────────────────── */}
      <aside className="hidden lg:flex flex-col w-56 xl:w-64 bg-sidebar border-r border-sidebar-border shrink-0 sticky top-0 h-screen">
        <SidebarContent {...sidebarProps} />
      </aside>

      {/* ── Mobile sidebar overlay ────────────────────────────────────── */}
      {sidebarOpen && (
        <div className="lg:hidden fixed inset-0 z-40 flex">
          <div className="fixed inset-0 bg-foreground/20 backdrop-blur-sm" onClick={() => setSidebarOpen(false)} />
          <aside className="relative z-50 w-56 bg-sidebar border-r border-sidebar-border flex flex-col">
            <button
              className="absolute top-4 right-4 text-sidebar-foreground/60 hover:text-sidebar-foreground"
              onClick={() => setSidebarOpen(false)}
            >
              <X className="w-5 h-5" />
            </button>
            <SidebarContent {...sidebarProps} onNavClick={() => setSidebarOpen(false)} />
          </aside>
        </div>
      )}

      {/* ── Main area ─────────────────────────────────────────────────── */}
      <div className="flex-1 flex flex-col min-w-0">

        {/* Mobile header */}
        <header className="lg:hidden sticky top-0 z-30 bg-card/80 backdrop-blur border-b border-border flex items-center justify-between px-4 h-14">
          <div className="flex items-center gap-3">
            <button onClick={() => setSidebarOpen(true)} className="text-muted-foreground hover:text-foreground">
              <Menu className="w-5 h-5" />
            </button>
            <div className="flex items-center gap-2">
              <Clock className="w-4 h-4 text-primary" />
              <span className="text-foreground text-sm font-medium">{t('common.appName')}</span>
            </div>
            {canSwitch && (
              <button onClick={handleSwitch}
                className="text-xs text-muted-foreground hover:text-primary flex items-center gap-1 border border-border rounded-md px-2 py-1">
                {switchTarget!.label} <ChevronRight className="w-3 h-3" />
              </button>
            )}
          </div>
          <div className="flex items-center gap-2">
            <LanguageSwitcher />
            <div className="flex bg-muted rounded-lg p-0.5">
              {themeOptions.map(opt => (
                <button key={opt.value} onClick={() => setTheme(opt.value)} title={opt.label}
                  className={`p-1.5 rounded-md transition-colors ${theme === opt.value ? 'bg-card text-foreground shadow-sm' : 'text-muted-foreground'}`}>
                  {opt.icon}
                </button>
              ))}
            </div>
            <button
              onClick={notificationsPath ? () => navigate(notificationsPath) : undefined}
              className="relative p-2 rounded-lg hover:bg-accent text-muted-foreground"
            >
              <Bell className="w-5 h-5" />
              {unreadCount > 0 && (
                <span className="absolute top-1 right-1 w-4 h-4 bg-destructive text-destructive-foreground text-xs rounded-full flex items-center justify-center leading-none">
                  {unreadCount}
                </span>
              )}
            </button>
          </div>
        </header>

        {/* Desktop header bar */}
        <header className="hidden lg:flex sticky top-0 z-20 bg-card/80 backdrop-blur border-b border-border items-center justify-between px-6 h-12">
          <div className="text-xs text-muted-foreground">{headerInfo ?? brandSubtitle}</div>
          <div className="flex items-center gap-2">
            <LanguageSwitcher />
            <div className="flex gap-0.5 bg-muted rounded-lg p-0.5">
              {themeOptions.map(opt => (
                <button key={opt.value} onClick={() => setTheme(opt.value)} title={opt.label}
                  className={`p-1.5 rounded-md transition-colors ${theme === opt.value ? 'bg-card text-foreground shadow-sm' : 'text-muted-foreground hover:text-foreground'}`}>
                  {opt.icon}
                </button>
              ))}
            </div>
            {canSwitch && (
              <button onClick={handleSwitch}
                className="text-xs text-muted-foreground hover:text-foreground flex items-center gap-1 border border-border rounded-lg px-2 py-1.5 hover:bg-accent transition-colors">
                {switchTarget!.label} <ChevronRight className="w-3 h-3" />
              </button>
            )}
            <button
              onClick={notificationsPath ? () => navigate(notificationsPath) : undefined}
              className="relative p-1.5 rounded-lg hover:bg-accent text-muted-foreground"
            >
              <Bell className="w-4 h-4" />
              {unreadCount > 0 && (
                <span className="absolute top-0.5 right-0.5 w-3.5 h-3.5 bg-destructive text-destructive-foreground text-xs rounded-full flex items-center justify-center leading-none">
                  {unreadCount}
                </span>
              )}
            </button>
            <div className="w-px h-5 bg-border mx-1" />
            <div className="flex items-center gap-2">
              <div className="w-7 h-7 bg-[oklch(0.488_0.243_264.376)] rounded-full flex items-center justify-center shrink-0">
                <span className="text-white text-xs font-medium">{user?.displayName?.[0]}</span>
              </div>
              <span className="text-xs text-foreground">{user?.displayName}</span>
              <button onClick={handleLogout} title={t('common.logout')}
                className="p-1.5 rounded-lg text-muted-foreground hover:text-destructive hover:bg-accent transition-colors">
                <LogOut className="w-3.5 h-3.5" />
              </button>
            </div>
          </div>
        </header>

        {/* Page content */}
        <main className={`flex-1 overflow-auto ${bottomNavItems ? 'pb-20 lg:pb-0' : ''}`}>
          <Outlet />
        </main>
      </div>

      {/* ── Mobile bottom nav (student mode) ─────────────────────────── */}
      {bottomNavItems && (
        <nav className="lg:hidden fixed bottom-0 left-0 right-0 z-30 bg-card/90 backdrop-blur border-t border-border">
          <div className="flex">
            {bottomNavItems.map(({ to, icon: Icon, label }) => (
              <NavLink
                key={to}
                to={to}
                className={({ isActive }) =>
                  `flex-1 flex flex-col items-center justify-center py-2 gap-0.5 transition-colors ${
                    isActive ? 'text-primary' : 'text-muted-foreground'
                  }`
                }
              >
                {({ isActive }) => (
                  <>
                    <Icon className={`w-5 h-5 transition-transform ${isActive ? 'scale-110' : ''}`} />
                    <span className="text-xs">{label}</span>
                  </>
                )}
              </NavLink>
            ))}
          </div>
        </nav>
      )}
    </div>
  );
}
