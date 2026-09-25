import { Dialog, DialogContent, DialogHeader, DialogTitle } from './ui/dialog';
import { HELP, type HelpTopic } from '../lib/help';

/**
 * Окно справки (ANO-186): одно на все экраны, тексты — в lib/help.ts. Справка кармашка длиннее
 * экрана телефона, поэтому окно прокручивается само, а не растягивается за край.
 */
export default function HelpDialog({ topic, open, onOpenChange }: {
    topic: HelpTopic;
    open: boolean;
    onOpenChange: (v: boolean) => void;
}) {
    const article = HELP[topic];
    return (
        <Dialog open={open} onOpenChange={onOpenChange}>
            <DialogContent className="max-w-md max-h-[85dvh] overflow-y-auto">
                <DialogHeader>
                    <DialogTitle>{article.title}</DialogTitle>
                </DialogHeader>
                <div className="space-y-4 text-sm leading-relaxed">
                    {article.sections.map((section, i) => (
                        <section key={i} className="space-y-2">
                            {section.heading && <h3 className="font-semibold">{section.heading}</h3>}
                            {section.paragraphs.map((p, j) => <p key={j}>{p}</p>)}
                        </section>
                    ))}
                </div>
            </DialogContent>
        </Dialog>
    );
}
