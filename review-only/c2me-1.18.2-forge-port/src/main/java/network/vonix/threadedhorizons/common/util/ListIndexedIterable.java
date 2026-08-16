package network.vonix.threadedhorizons.common.util;

import net.minecraft.core.IdMap;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.Iterator;
import java.util.List;

public record ListIndexedIterable<T>(List<T> delegate) implements IdMap<T> {

    @Override
    public int getId(T entry) {
        return delegate.indexOf(entry);
    }

    @Nullable
    @Override
    public T byId(int index) {
        return delegate.get(index);
    }

    @Override
    public int size() {
        return delegate.size();
    }

    @NotNull
    @Override
    public Iterator<T> iterator() {
        return delegate.iterator();
    }
}
