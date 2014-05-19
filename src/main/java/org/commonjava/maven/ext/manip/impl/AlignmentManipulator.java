package org.commonjava.maven.ext.manip.impl;

import static org.commonjava.maven.ext.manip.IdUtils.ga;

import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.Set;

import org.apache.maven.model.Model;
import org.apache.maven.project.MavenProject;
import org.codehaus.plexus.logging.Logger;
import org.commonjava.maven.ext.manip.IdUtils;
import org.commonjava.maven.ext.manip.ManipulationException;
import org.commonjava.maven.ext.manip.resolver.EffectiveModelBuilder;
import org.commonjava.maven.ext.manip.state.BOMState;
import org.commonjava.maven.ext.manip.state.ManipulationSession;

/**
 * {@link Manipulator} base implementation used by the property, dependency and plugin manipulators.
 * Configuration is stored in a {@link BOMState} instance, which is in turn stored in the {@link ManipulationSession}.
 */
public abstract class AlignmentManipulator
    implements Manipulator
{
    protected enum RemoteType
    {
        PROPERTY, PLUGIN, DEPENDENCY
    };

    protected Logger baseLogger;

    protected AlignmentManipulator()
    {
    }

    public AlignmentManipulator( final Logger logger )
    {
        this.baseLogger = logger;
    }

    /**
     * No prescanning required for BOM manipulation.
     */
    @Override
    public void scan( final List<MavenProject> projects, final ManipulationSession session )
        throws ManipulationException
    {
    }

    /**
     * Initialize the {@link BOMState} state holder in the {@link ManipulationSession}. This state holder detects
     * version-change configuration from the Maven user properties (-D properties from the CLI) and makes it available for
     * later invocations of {@link AlignmentManipulator#scan(List, ManipulationSession)} and the apply* methods.
     */
    @Override
    public void init( final ManipulationSession session )
    {
        final Properties userProps = session.getUserProperties();
        session.setState( new BOMState( userProps ) );
    }

    /**
     * Apply the reporting and repository removal changes to the list of {@link MavenProject}'s given.
     * This happens near the end of the Maven session-bootstrapping sequence, before the projects are
     * discovered/read by the main Maven build initialization.
     */
    @Override
    public Set<MavenProject> applyChanges( final List<MavenProject> projects, final ManipulationSession session )
        throws ManipulationException
    {
        final BOMState state = session.getState( BOMState.class );

        if ( !session.isEnabled() || !state.isEnabled() )
        {
            baseLogger.debug( "Version Manipulator: Nothing to do!" );
            return Collections.emptySet();
        }

        final Map<String, Model> manipulatedModels = session.getManipulatedModels();
        final Map<String, String> overrides = loadRemoteBOM(state);
        final Set<MavenProject> changed = new HashSet<MavenProject>();

        for ( final MavenProject project : projects )
        {
            final String ga = ga( project );
            final Model model = manipulatedModels.get( ga );

            if (overrides.size() > 0)
            {
                apply (session, project, model, overrides);

                changed.add( project );
            }
        }

        return changed;
    }

    /**
     * Get property mappings from a remote POM
     *
     * @return Map between the GA of the plugin and the version of the plugin. If the system property is not set,
     *         returns an empty map.
     */
    protected Map<String, String> loadRemoteOverrides( final RemoteType rt, final String remoteMgmt )
        throws ManipulationException
    {
        final Map<String, String> overrides = new HashMap<String, String>();

        if ( remoteMgmt == null || remoteMgmt.length() == 0 )
        {
            return overrides;
        }

        final String[] remoteMgmtPomGAVs = remoteMgmt.split( "," );

        // Iterate in reverse order so that the first GAV in the list overwrites the last
        for ( int i = ( remoteMgmtPomGAVs.length - 1 ); i > -1; --i )
        {
            final String nextGAV = remoteMgmtPomGAVs[i];

            if ( !IdUtils.validGav( nextGAV ) )
            {
                baseLogger.warn( "Skipping invalid remote management GAV: " + nextGAV );
                continue;
            }
            switch ( rt )
            {
                case PROPERTY:
                    overrides.putAll( EffectiveModelBuilder.getInstance()
                                                           .getRemotePropertyMappingOverrides( nextGAV ) );
                    break;
                case PLUGIN:
                    overrides.putAll( EffectiveModelBuilder.getInstance()
                                                           .getRemotePluginVersionOverrides( nextGAV ) );
                    break;
                case DEPENDENCY:
                    overrides.putAll( EffectiveModelBuilder.getInstance().getRemoteDependencyVersionOverrides( nextGAV ) );
                    break;

            }
        }

        return overrides;
    }


    /**
     * Abstract method to be implemented by subclasses. Returns the remote bom.
     * @param state
     * @param session TODO
     * @param project TODO
     * @param model
     * @param override
     * @throws ManipulationException
     */
    protected abstract Map<String, String> loadRemoteBOM (BOMState state) throws ManipulationException;

    /**
     * Abstract method to be implemented by subclasses. Performs the actual injection on the pom file.
     * @param session TODO
     * @param project TODO
     * @param model
     * @param override
     * @throws ManipulationException TODO
     */
    protected abstract void apply (ManipulationSession session, MavenProject project, Model model, Map<String, String> override) throws ManipulationException;
}