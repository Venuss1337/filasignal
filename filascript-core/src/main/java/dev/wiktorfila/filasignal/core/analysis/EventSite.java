package dev.wiktorfila.filasignal.core.analysis;

import dev.wiktorfila.filasignal.core.host.HostRegistry;

import java.util.List;

/**
 * How one {@code on} declaration was resolved.
 *
 * @param binding    the {@code event} binding visible in the handler body
 * @param name       the event name delivered by the host: the declared dotted name, or for a source event the
 *                   unqualified event name such as {@code entityEnter}
 * @param filters    how many leading parenthesized expressions are activation-time filters
 * @param parameters handler parameters bound to the event's values in order
 * @param source     the file constant holding the device reference in {@code on gate.entityEnter(entity)}, or null
 * @param definition the declared event, or null for an undeclared event in a permissive registry
 */
public record EventSite(Symbol binding, String name, int filters, List<Symbol> parameters, Symbol source,
                        HostRegistry.Event definition) {
    public EventSite {
        parameters = List.copyOf(parameters);
    }
}
