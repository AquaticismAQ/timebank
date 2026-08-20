import { BrowserRouter, Routes, Route, Navigate } from 'react-router';
import { createContext, useContext, useState, useEffect, useCallback } from 'react';
import { Toaster } from 'sonner';
import { type User } from './api/types';
import { api } from './api/client';
import { setUnauthorizedHandler, resetUnauthorizedState } from './api/http';
import i18n from '../i18n';

// ── Shared operation log ───────────────────────────────────────────────────

export interface LogEntry {
  id: string;
  operator: string;
  action: string;
  target: string;
  time: string;
  category: 'review' | 'project' | 'import' | 'system' | 'student';
  memo?: string;
}

const SEED_LOGS: LogEntry[] = [
  { id: 'l1', operator: 'Teacher Wei Chen', action: 'Approved service record', target: 'Jia Ming - Library Organization', time: '2026-07-18 10:30', category: 'review' },
  { id: 'l2', operator: 'Alex Lin', action: 'Returned service record', target: 'Jia Ming - Community Drowning Prevention Campaign', time: '2026-07-14 14:30', category: 'review' },
  { id: 'l3', operator: 'Teacher Wei Chen', action: 'Published project', target: 'Rural Teaching Support Team (Summer)', time: '2026-07-10 10:00', category: 'project' },
  { id: 'l4', operator: 'Teacher Wei Chen', action: 'Imported student data', target: 'Bulk import of 186 records', time: '2026-07-01 09:15', category: 'import' },
  { id: 'l5', operator: 'Teacher Mei Zhao', action: 'Created user account', target: 'New teacher account Jordan', time: '2026-06-20 11:00', category: 'system' },
];

interface LogsContextValue {
  logs: LogEntry[];
  addLog: (entry: Omit<LogEntry, 'id' | 'time'>) => void;
}

export const LogsContext = createContext<LogsContextValue>({ logs: SEED_LOGS, addLog: () => {} });
export const useLogs = () => useContext(LogsContext);

function LogsProvider({ children }: { children: React.ReactNode }) {
  const [logs, setLogs] = useState<LogEntry[]>(SEED_LOGS);
  const addLog = useCallback((entry: Omit<LogEntry, 'id' | 'time'>) => {
    const now = new Date();
    const pad = (n: number) => String(n).padStart(2, '0');
    const time = `${now.getFullYear()}-${pad(now.getMonth() + 1)}-${pad(now.getDate())} ${pad(now.getHours())}:${pad(now.getMinutes())}`;
    setLogs(prev => [{ ...entry, id: `l${Date.now()}`, time }, ...prev]);
  }, []);
  return <LogsContext.Provider value={{ logs, addLog }}>{children}</LogsContext.Provider>;
}
import { LoginPage } from './components/auth/LoginPage';
import { StudentLayout } from './components/layout/StudentLayout';
import { AdminLayout } from './components/layout/AdminLayout';
import { StudentHome } from './components/student/StudentHome';
import { ProjectCenter } from './components/student/ProjectCenter';
import { ServiceRecords } from './components/student/ServiceRecords';
import { AccountPage } from './components/student/AccountPage';
import { ProgressPage } from './components/student/ProgressPage';
import { ProfilePage } from './components/student/ProfilePage';
import { NotificationsPage } from './components/student/NotificationsPage';
import { EventProposals } from './components/student/EventProposals';
import { AdminDashboard } from './components/admin/AdminDashboard';
import { AuditCenter } from './components/admin/AuditCenter';
import { CaseReview } from './components/admin/CaseReview';
import { ProposalReview } from './components/admin/ProposalReview';
import { ProjectManagement } from './components/admin/ProjectManagement';
import { StudentList } from './components/admin/StudentList';
import { ReportsPage } from './components/admin/ReportsPage';
import { AccountManagement } from './components/admin/AccountManagement';
import { SystemAdmin } from './components/admin/SystemAdmin';
import { AdminProfilePage } from './components/admin/AdminProfilePage';

// Theme context
type Theme = 'light' | 'dark' | 'auto';

interface ThemeContextValue {
  theme: Theme;
  resolvedTheme: 'light' | 'dark';
  setTheme: (t: Theme) => void;
}

export const ThemeContext = createContext<ThemeContextValue>({
  theme: 'auto',
  resolvedTheme: 'light',
  setTheme: () => {},
});

// Auth context
interface AuthContextValue {
  user: User | null;
  login: (username: string, password: string) => Promise<boolean>;
  logout: () => void;
  /** Merges a partial update (e.g. after `PATCH /me/profile`) into the current session's user, persisted to localStorage like login does. */
  updateUser: (patch: Partial<User>) => void;
}

export const AuthContext = createContext<AuthContextValue>({
  user: null,
  login: () => Promise.resolve(false),
  logout: () => {},
  updateUser: () => {},
});

export const useAuth = () => useContext(AuthContext);
export const useTheme = () => useContext(ThemeContext);

/** Permission check from `/me` (or mock user fixtures). Undefined permissions → allow all (legacy sessions). */
export function usePermissions() {
  const { user } = useContext(AuthContext);
  const hasPermission = useCallback((code: string) => {
    if (!user?.permissions) return true;
    return user.permissions.includes(code);
  }, [user]);
  return { hasPermission, permissions: user?.permissions ?? [] };
}

function getSystemTheme(): 'light' | 'dark' {
  const hour = new Date().getHours();
  return hour >= 7 && hour < 19 ? 'light' : 'dark';
}

function ThemeProvider({ children }: { children: React.ReactNode }) {
  const [theme, setThemeState] = useState<Theme>(() => {
    return (localStorage.getItem('timebank-theme') as Theme) || 'auto';
  });

  const resolvedTheme: 'light' | 'dark' = theme === 'auto' ? getSystemTheme() : theme;

  useEffect(() => {
    const root = document.documentElement;
    root.classList.toggle('dark', resolvedTheme === 'dark');
  }, [resolvedTheme]);

  const setTheme = useCallback((t: Theme) => {
    setThemeState(t);
    localStorage.setItem('timebank-theme', t);
  }, []);

  return (
    <ThemeContext.Provider value={{ theme, resolvedTheme, setTheme }}>
      {children}
    </ThemeContext.Provider>
  );
}

function AuthProvider({ children }: { children: React.ReactNode }) {
  const [user, setUser] = useState<User | null>(() => {
    const stored = localStorage.getItem('timebank-user');
    return stored ? JSON.parse(stored) : null;
  });

  const login = useCallback(async (username: string, password: string): Promise<boolean> => {
    try {
      const { user: found } = await api.auth.login(username, password);
      setUser(found);
      localStorage.setItem('timebank-user', JSON.stringify(found));
      // I3.4: the server's preferredLanguage is authoritative once a real
      // session exists — sync it now rather than leaving whatever
      // localStorage['timebank-lang'] happened to hold from a previous
      // device/session/incognito window.
      if (found.preferredLanguage) {
        const code = found.preferredLanguage === 'zh-CN' ? 'zh' : 'en';
        i18n.changeLanguage(code);
        localStorage.setItem('timebank-lang', code);
      }
      resetUnauthorizedState();
      return true;
    } catch {
      return false;
    }
  }, []);

  const logout = useCallback(() => {
    setUser(null);
    localStorage.removeItem('timebank-user');
  }, []);

  // Any API call anywhere can be the one that discovers the session expired
  // server-side (idle timeout, revoked cookie, ...) — http.ts calls this
  // single handler the first time any call gets back 401
  // AUTHENTICATION_REQUIRED, so we don't rely on the stale cached `user` in
  // localStorage (which RequireAuth otherwise trusts forever) to notice.
  useEffect(() => {
    setUnauthorizedHandler(() => {
      logout();
      if (window.location.pathname !== '/login') {
        window.location.assign('/login');
      }
    });
  }, [logout]);

  const updateUser = useCallback((patch: Partial<User>) => {
    setUser(prev => {
      if (!prev) return prev;
      const next = { ...prev, ...patch };
      localStorage.setItem('timebank-user', JSON.stringify(next));
      return next;
    });
  }, []);

  return (
    <AuthContext.Provider value={{ user, login, logout, updateUser }}>
      {children}
    </AuthContext.Provider>
  );
}

/**
 * 07-RoleFinetune.md §2.5: every user holds exactly one role, so exactly one
 * workspace — no picker, no ambiguous-workspace state to redirect through.
 * A mismatch (e.g. a student hitting an `/admin/*` route) sends them
 * straight to their own single workspace's home, not to a chooser.
 */
function RequireAuth({ children, workspace }: { children: React.ReactNode; workspace?: 'student' | 'admin' }) {
  const { user } = useAuth();
  if (!user) return <Navigate to="/login" replace />;
  const ownWorkspace = user.workspaces[0];
  if (workspace && ownWorkspace !== workspace) {
    return <Navigate to={ownWorkspace === 'student' ? '/student/home' : '/admin/dashboard'} replace />;
  }
  return <>{children}</>;
}

export default function App() {
  return (
    <ThemeProvider>
      <AuthProvider>
        <LogsProvider>
        <BrowserRouter>
          <Routes>
            <Route path="/login" element={<LoginPage />} />

            {/* Student portal */}
            <Route path="/student" element={
              <RequireAuth workspace="student">
                <StudentLayout />
              </RequireAuth>
            }>
              <Route index element={<Navigate to="/student/home" replace />} />
              <Route path="home" element={<StudentHome />} />
              <Route path="projects" element={<ProjectCenter />} />
              <Route path="records" element={<ServiceRecords />} />
              <Route path="account" element={<AccountPage />} />
              <Route path="progress" element={<ProgressPage />} />
              <Route path="proposals" element={<EventProposals />} />
              <Route path="profile" element={<ProfilePage />} />
              <Route path="notifications" element={<NotificationsPage />} />
            </Route>

            {/* Admin/teacher portal */}
            <Route path="/admin" element={
              <RequireAuth workspace="admin">
                <AdminLayout />
              </RequireAuth>
            }>
              <Route index element={<Navigate to="/admin/dashboard" replace />} />
              <Route path="dashboard" element={<AdminDashboard />} />
              <Route path="audit" element={<AuditCenter />} />
              <Route path="cases" element={<CaseReview />} />
              <Route path="proposals" element={<ProposalReview />} />
              <Route path="projects" element={<ProjectManagement />} />
              <Route path="students" element={<StudentList />} />
              <Route path="accounts" element={<AccountManagement />} />
              <Route path="reports" element={<ReportsPage />} />
              <Route path="system" element={<SystemAdmin />} />
              <Route path="notifications" element={<NotificationsPage />} />
              <Route path="profile" element={<AdminProfilePage />} />
            </Route>

            <Route path="/" element={<Navigate to="/login" replace />} />
            <Route path="*" element={<Navigate to="/login" replace />} />
          </Routes>
        </BrowserRouter>
        <Toaster position="top-center" richColors />
        </LogsProvider>
      </AuthProvider>
    </ThemeProvider>
  );
}
