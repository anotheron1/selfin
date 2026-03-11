import { NavLink } from 'react-router-dom';
import { LayoutDashboard, CalendarDays, PiggyBank, BarChart2, Settings } from 'lucide-react';

const navItems = [
    { to: '/', icon: LayoutDashboard, label: 'Дашборд' },
    { to: '/budget', icon: CalendarDays, label: 'Бюджет' },
    { to: '/funds', icon: PiggyBank, label: 'Цели' },
    { to: '/analytics', icon: BarChart2, label: 'Аналитика' },
    { to: '/settings', icon: Settings, label: 'Настройки' },
];

/**
 * Фиксированная нижняя навигационная панель приложения.
 * Содержит 5 вкладок: Дашборд, Бюджет, Фонды, Аналитика, Настройки.
 * Активная вкладка подсвечивается акцентным цветом через `NavLink`.
 * Высота задана CSS-переменной `--nav-height` (64px) и учитывается в отступах страниц.
 */
export default function BottomNav() {
    return (
        <nav className="fixed bottom-0 left-0 right-0 z-50"
            style={{
                background: 'var(--color-surface)',
                borderTop: '1px solid var(--color-border)',
                height: 'var(--nav-height)',
            }}>
            <div className="flex items-stretch h-full max-w-2xl mx-auto">
                {navItems.map(({ to, icon: Icon, label }) => (
                    <NavLink
                        key={to}
                        to={to}
                        end={to === '/'}
                        className="flex flex-col items-center justify-center flex-1 gap-1 text-xs font-medium transition-colors"
                        style={({ isActive }) => ({
                            color: isActive ? 'var(--color-accent)' : 'var(--color-text-muted)',
                        })}
                    >
                        <Icon size={20} strokeWidth={1.8} />
                        {label}
                    </NavLink>
                ))}
            </div>
        </nav>
    );
}
