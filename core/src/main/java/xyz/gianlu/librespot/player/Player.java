package xyz.gianlu.librespot.player;

import org.apache.log4j.Logger;
import org.jetbrains.annotations.NotNull;
import xyz.gianlu.librespot.common.Utils;
import xyz.gianlu.librespot.common.proto.Spirc;
import xyz.gianlu.librespot.core.Session;
import xyz.gianlu.librespot.spirc.FrameListener;
import xyz.gianlu.librespot.spirc.SpotifyIrc;

import java.io.IOException;
import java.util.*;

/**
 * @author Gianlu
 */
public class Player implements FrameListener, TrackHandler.Listener {
    private static final Logger LOGGER = Logger.getLogger(Player.class);
    private final Session session;
    private final SpotifyIrc spirc;
    private final Spirc.State.Builder state;
    private final PlayerConfiguration conf;
    private final CacheManager cacheManager;
    private TrackHandler trackHandler;
    private TrackHandler preloadTrackHandler;
    private long shuffleSeed;

    public Player(@NotNull PlayerConfiguration conf, @NotNull CacheManager.CacheConfiguration cacheConfiguration, @NotNull Session session) {
        this.conf = conf;
        this.session = session;
        this.spirc = session.spirc();
        this.state = initState();

        try {
            this.cacheManager = new CacheManager(cacheConfiguration);
        } catch (IOException ex) {
            throw new RuntimeException(ex);
        }

        spirc.addListener(this);
    }

    private static int[] getShuffleExchanges(int size, long seed) {
        int[] exchanges = new int[size - 1];
        Random rand = new Random(seed);
        for (int i = size - 1; i > 0; i--) {
            int n = rand.nextInt(i + 1);
            exchanges[size - 1 - i] = n;
        }
        return exchanges;
    }

    public void playPause() {
        handlePlayPause();
    }

    public void play() {
        handlePlay();
    }

    public void pause() {
        handlePause();
    }

    public void next() {
        handleNext();
    }

    public void previous() {
        handlePrev();
    }

    @NotNull
    private Spirc.State.Builder initState() {
        return Spirc.State.newBuilder()
                .setPositionMeasuredAt(0)
                .setPositionMs(0)
                .setShuffle(false)
                .setRepeat(false)
                .setStatus(Spirc.PlayStatus.kPlayStatusStop);
    }

    @Override
    public void frame(@NotNull Spirc.Frame frame) {
        switch (frame.getTyp()) {
            case kMessageTypeNotify:
                if (spirc.deviceState().getIsActive() && frame.getDeviceState().getIsActive()) {
                    spirc.deviceState().setIsActive(false);
                    state.setStatus(Spirc.PlayStatus.kPlayStatusStop);
                    if (trackHandler != null) trackHandler.sendStop();
                    stateUpdated();
                }
                break;
            case kMessageTypeLoad:
                handleLoad(frame);
                break;
            case kMessageTypePlay:
                handlePlay();
                break;
            case kMessageTypePause:
                handlePause();
                break;
            case kMessageTypePlayPause:
                handlePlayPause();
                break;
            case kMessageTypeNext:
                handleNext();
                break;
            case kMessageTypePrev:
                handlePrev();
                break;
            case kMessageTypeSeek:
                handleSeek(frame.getPosition());
                break;
            case kMessageTypeReplace:
                updatedTracks(frame);
                stateUpdated();
                break;
            case kMessageTypeRepeat:
                state.setRepeat(frame.getState().getRepeat());
                stateUpdated();
                break;
            case kMessageTypeShuffle:
                state.setShuffle(frame.getState().getShuffle());
                handleShuffle();
                break;
            case kMessageTypeVolume:
                handleSetVolume(frame.getVolume());
                break;
            case kMessageTypeVolumeDown:
                handleVolumeDown();
                break;
            case kMessageTypeVolumeUp:
                handleVolumeUp();
                break;
        }
    }

    private void handlePlayPause() {
        if (state.getStatus() == Spirc.PlayStatus.kPlayStatusPlay) handlePause();
        else if (state.getStatus() == Spirc.PlayStatus.kPlayStatusPause) handlePlay();
    }

    private void handleSetVolume(int volume) {
        spirc.deviceState().setVolume(volume);

        if (trackHandler != null) {
            PlayerRunner.Controller controller = trackHandler.controller();
            if (controller != null) controller.setVolume(volume);
        }

        stateUpdated();
    }

    private void handleVolumeDown() {
        if (trackHandler != null) {
            PlayerRunner.Controller controller = trackHandler.controller();
            if (controller != null) spirc.deviceState().setVolume(controller.volumeDown());
            stateUpdated();
        }
    }

    private void handleVolumeUp() {
        if (trackHandler != null) {
            PlayerRunner.Controller controller = trackHandler.controller();
            if (controller != null) spirc.deviceState().setVolume(controller.volumeUp());
            stateUpdated();
        }
    }

    private void stateUpdated() {
        spirc.deviceStateUpdated(state);
    }

    private int getPosition() {
        int diff = (int) (System.currentTimeMillis() - state.getPositionMeasuredAt());
        return state.getPositionMs() + diff;
    }

    private void shuffleTracks() {
        shuffleSeed = session.random().nextLong();

        List<Spirc.TrackRef> tracks = new ArrayList<>(state.getTrackList());
        if (state.getPlayingTrackIndex() != 0) {
            Collections.swap(tracks, 0, state.getPlayingTrackIndex());
            state.setPlayingTrackIndex(0);
        }

        int size = tracks.size() - 1;
        int[] exchanges = getShuffleExchanges(size, shuffleSeed);
        for (int i = size - 1; i > 1; i--) {
            int n = exchanges[size - 1 - i];
            Collections.swap(tracks, i, n + 1);
        }

        state.clearTrack();
        state.addAllTrack(tracks);
    }

    private void unshuffleTracks() {
        List<Spirc.TrackRef> tracks = new ArrayList<>(state.getTrackList());
        if (state.getPlayingTrackIndex() != 0) {
            Collections.swap(tracks, 0, state.getPlayingTrackIndex());
            state.setPlayingTrackIndex(0);
        }

        int size = tracks.size() - 1;
        int[] exchanges = getShuffleExchanges(size, shuffleSeed);
        for (int i = 2; i < size; i++) {
            int n = exchanges[size - i - 1];
            Collections.swap(tracks, i, n + 1);
        }

        state.clearTrack();
        state.addAllTrack(tracks);
    }

    private void handleShuffle() {
        if (state.getShuffle()) shuffleTracks();
        else unshuffleTracks();
        stateUpdated();
    }

    private void handleSeek(int pos) {
        state.setPositionMs(pos);
        state.setPositionMeasuredAt(System.currentTimeMillis());
        if (trackHandler != null) trackHandler.sendSeek(pos);
        stateUpdated();
    }

    private void updatedTracks(@NotNull Spirc.Frame frame) {
        state.setPlayingTrackIndex(frame.getState().getPlayingTrackIndex());
        state.clearTrack();
        state.addAllTrack(frame.getState().getTrackList());
        state.setContextUri(frame.getState().getContextUri());
        state.setRepeat(frame.getState().getRepeat());
        state.setShuffle(frame.getState().getShuffle());
    }

    @Override
    public void finishedLoading(@NotNull TrackHandler handler, boolean play) {
        if (handler == trackHandler) {
            if (play) state.setStatus(Spirc.PlayStatus.kPlayStatusPlay);
            else state.setStatus(Spirc.PlayStatus.kPlayStatusPause);
            stateUpdated();
        } else if (handler == preloadTrackHandler) {
            LOGGER.trace("Preloaded track is ready.");
        }
    }

    @Override
    public void loadingError(@NotNull TrackHandler handler, @NotNull Exception ex) {
        if (handler == trackHandler) {
            LOGGER.fatal("Failed loading track!", ex);
            state.setStatus(Spirc.PlayStatus.kPlayStatusStop);
            stateUpdated();
        } else if (handler == preloadTrackHandler) {
            LOGGER.warn("Preloaded track loading failed!", ex);
            preloadTrackHandler = null;
        }
    }

    @Override
    public void endOfTrack(@NotNull TrackHandler handler) {
        if (handler == trackHandler) {
            LOGGER.trace("End of track. Proceeding with next.");
            handleNext();
        }
    }

    @Override
    public void preloadNextTrack(@NotNull TrackHandler handler) {
        if (handler == trackHandler) {
            Spirc.TrackRef next = state.getTrack(getQueuedTrack(false));

            preloadTrackHandler = new TrackHandler(session, cacheManager, conf, this);
            preloadTrackHandler.sendLoad(next, false, 0);
            LOGGER.trace("Started next track preload, gid: " + Utils.bytesToHex(next.getGid()));
        }
    }

    private void handleLoad(@NotNull Spirc.Frame frame) {
        if (!spirc.deviceState().getIsActive()) {
            spirc.deviceState()
                    .setIsActive(true)
                    .setBecameActiveAt(System.currentTimeMillis());
        }

        updatedTracks(frame);

        if (state.getTrackCount() > 0) {
            state.setPositionMs(frame.getState().getPositionMs());
            state.setPositionMeasuredAt(System.currentTimeMillis());

            loadTrack(frame.getState().getStatus() == Spirc.PlayStatus.kPlayStatusPlay);
        } else {
            state.setStatus(Spirc.PlayStatus.kPlayStatusStop);
        }

        stateUpdated();
    }

    private void loadTrack(boolean play) {
        if (trackHandler != null) trackHandler.close();

        Spirc.TrackRef ref = state.getTrack(state.getPlayingTrackIndex());
        if (preloadTrackHandler != null && preloadTrackHandler.isTrack(ref)) {
            trackHandler = preloadTrackHandler;
            preloadTrackHandler = null;
            trackHandler.sendSeek(state.getPositionMs());
            if (play) {
                state.setStatus(Spirc.PlayStatus.kPlayStatusPlay);
                trackHandler.sendPlay();
            } else {
                state.setStatus(Spirc.PlayStatus.kPlayStatusPause);
            }
        } else {
            trackHandler = new TrackHandler(session, cacheManager, conf, this);
            trackHandler.sendLoad(ref, play, state.getPositionMs());
            state.setStatus(Spirc.PlayStatus.kPlayStatusLoading);
        }

        stateUpdated();
    }

    private void handlePlay() {
        if (state.getStatus() == Spirc.PlayStatus.kPlayStatusPause) {
            if (trackHandler != null) trackHandler.sendPlay();
            state.setStatus(Spirc.PlayStatus.kPlayStatusPlay);
            state.setPositionMeasuredAt(System.currentTimeMillis());
            stateUpdated();
        }
    }

    private void handlePause() {
        if (state.getStatus() == Spirc.PlayStatus.kPlayStatusPlay) {
            if (trackHandler != null) trackHandler.sendPause();
            state.setStatus(Spirc.PlayStatus.kPlayStatusPause);

            long now = System.currentTimeMillis();
            int pos = state.getPositionMs();
            int diff = (int) (now - state.getPositionMeasuredAt());
            state.setPositionMs(pos + diff);
            state.setPositionMeasuredAt(now);
            stateUpdated();
        }
    }

    private void handleNext() {
        int newTrack = getQueuedTrack(true);
        boolean play = true;
        if (newTrack >= state.getTrackCount()) {
            newTrack = 0;
            play = state.getRepeat();
        }

        state.setPlayingTrackIndex(newTrack);
        state.setPositionMs(0);
        state.setPositionMeasuredAt(System.currentTimeMillis());

        loadTrack(play);
    }

    private void handlePrev() {
        if (getPosition() < 3000) {
            List<Spirc.TrackRef> queueTracks = new ArrayList<>();
            Iterator<Spirc.TrackRef> iter = state.getTrackList().iterator();
            while (iter.hasNext()) {
                Spirc.TrackRef track = iter.next();
                if (track.getQueued()) {
                    queueTracks.add(track);
                    iter.remove();
                }
            }

            int current = state.getPlayingTrackIndex();
            int newIndex;
            if (current > 0) newIndex = current - 1;
            else if (state.getRepeat()) newIndex = state.getTrackCount() - 1;
            else newIndex = 0;

            for (int i = 0; i < queueTracks.size(); i++)
                state.getTrackList().add(newIndex + 1 + i, queueTracks.get(i));

            state.setPlayingTrackIndex(newIndex);
            state.setPositionMs(0);
            state.setPositionMeasuredAt(System.currentTimeMillis());

            loadTrack(true);
        } else {
            state.setPositionMs(0);
            state.setPositionMeasuredAt(System.currentTimeMillis());
            if (trackHandler != null) trackHandler.sendSeek(0);
            stateUpdated();
        }
    }

    private int getQueuedTrack(boolean consume) {
        int current = state.getPlayingTrackIndex();
        if (state.getTrack(current).getQueued()) {
            if (consume) state.removeTrack(current);
            return current;
        }

        return current + 1;
    }

    public interface PlayerConfiguration {
        @NotNull
        TrackHandler.AudioQuality preferredQuality();

        boolean preloadEnabled();

        float normalisationPregain();
    }
}
